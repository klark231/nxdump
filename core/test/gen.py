import os, struct, random, json, sys
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

R = random.Random(1234)
def rb(n): return bytes(R.getrandbits(8) for _ in range(n))
def align(n, a): return (n + a - 1) // a * a
def pad(b, a): return b + b"\0" * (align(len(b), a) - len(b))

def ecb_enc(k, d): e = Cipher(algorithms.AES(k), modes.ECB()).encryptor(); return e.update(d) + e.finalize()
def ctr(k, iv, d): e = Cipher(algorithms.AES(k), modes.CTR(iv)).encryptor(); return e.update(d) + e.finalize()
def xts_enc_sector(k, sector, d):
    tw = b"\0" * 8 + struct.pack(">Q", sector)
    e = Cipher(algorithms.AES(k), modes.XTS(tw)).encryptor(); return e.update(d) + e.finalize()

HEADER_KEY = rb(32); KAK = rb(16); TITLEKEK = rb(16); GEN = 2  # crypto_type2 = 3 -> gen 2
# ---------------- containers
def pfs0(files):
    names = list(files); strtab = b""; offs = []
    for n in names: offs.append(len(strtab)); strtab += n.encode() + b"\0"
    strtab = pad(strtab, 0x20); ent = b""; o = 0
    for i, n in enumerate(names):
        ent += struct.pack("<QQII", o, len(files[n]), offs[i], 0); o += len(files[n])
    return b"PFS0" + struct.pack("<III", len(names), len(strtab), 0) + ent + strtab + b"".join(files[n] for n in names)

def hfs0(files):
    names = list(files); strtab = b""; offs = []
    for n in names: offs.append(len(strtab)); strtab += n.encode() + b"\0"
    strtab = pad(strtab, 0x20); ent = b""; o = 0
    for i, n in enumerate(names):
        ent += struct.pack("<QQIIQ", o, len(files[n]), offs[i], 0x200, 0) + b"\0" * 32; o += len(files[n])
    return b"HFS0" + struct.pack("<III", len(names), len(strtab), 0) + ent + strtab + b"".join(files[n] for n in names)

def romfs(files):
    dirs = {""}
    for p in files:
        parts = p.split("/")
        for i in range(1, len(parts)): dirs.add("/".join(parts[:i]))
    dl = sorted(dirs); di = {}; off = 0
    for d in dl: di[d] = off; off += 0x18 + align(len(d.split("/")[-1].encode()) if d else 0, 4)
    dsize = off
    fl = sorted(files); fi = {}; off = 0; doff = 0; fdata = b""
    for f in fl:
        fi[f] = (off, doff); off += 0x20 + align(len(f.split("/")[-1].encode()), 4)
        fdata += files[f]; doff += len(files[f]); doff = align(doff, 16); fdata = pad(fdata, 16)
    fsize = off
    def parent(p): return p.rsplit("/", 1)[0] if "/" in p else ""
    dbuf = bytearray(dsize); fbuf = bytearray(fsize)
    for d in dl:
        kids = [x for x in dl if x and parent(x) == d]; fk = [f for f in fl if parent(f) == d]
        nm = d.split("/")[-1].encode() if d else b""
        sib = [x for x in dl if x and parent(x) == parent(d)] if d else []
        s = sib[sib.index(d) + 1] if d and sib.index(d) + 1 < len(sib) else None
        struct.pack_into("<IIIIII", dbuf, di[d], di[parent(d)] if d else 0,
                         di[s] if s else 0xFFFFFFFF, di[kids[0]] if kids else 0xFFFFFFFF,
                         fi[fk[0]][0] if fk else 0xFFFFFFFF, 0xFFFFFFFF, len(nm))
        dbuf[di[d] + 0x18: di[d] + 0x18 + len(nm)] = nm
    for f in fl:
        nm = f.split("/")[-1].encode(); p = parent(f)
        sib = [x for x in fl if parent(x) == p]; s = sib[sib.index(f) + 1] if sib.index(f) + 1 < len(sib) else None
        struct.pack_into("<IIQQII", fbuf, fi[f][0], di[p], fi[s][0] if s else 0xFFFFFFFF,
                         fi[f][1], len(files[f]), 0xFFFFFFFF, len(nm))
        fbuf[fi[f][0] + 0x20: fi[f][0] + 0x20 + len(nm)] = nm
    dh_off = 0x200; dm_off = dh_off + 8; fh_off = align(dm_off + dsize, 8); fm_off = fh_off + 8
    data_off = align(fm_off + fsize, 0x200)
    hdr = struct.pack("<QQQQQQQQQQ", 0x50, dh_off, 8, dm_off, dsize, fh_off, 8, fm_off, fsize, data_off)
    img = bytearray(data_off); img[0:0x50] = hdr
    img[dm_off:dm_off + dsize] = dbuf; img[fm_off:fm_off + fsize] = fbuf
    return bytes(img) + fdata

# ---------------- NCA
def u64(x): return struct.pack("<Q", x)
def ctr_iv(hdr_ctr8, pos_abs, gen_override=None):
    up = bytearray(hdr_ctr8[::-1])
    if gen_override is not None: up[4:8] = struct.pack(">I", gen_override)
    return bytes(up) + struct.pack(">Q", pos_abs >> 4)

def fs_hdr(fs_type, hash_type, enc, gen=7, secure=1):
    h = bytearray(0x200); h[0:2] = b"\x02\x00"; h[2] = fs_type; h[3] = hash_type; h[4] = enc
    h[0x140:0x148] = struct.pack("<II", gen, secure); return h

def make_nca(ctype, tid, sections, rights=None, content_key=None):
    """sections: list of (fs_hdr bytearray, plaintext bytes, encrypt_fn(pos_abs, data)->bytes or None)"""
    content_key = content_key or rb(16)
    area = rb(16) + rb(16) + content_key + rb(16)
    pos = 0xC00; fs_entries = b""; fs_hdrs = b""; body = b""
    for (h, data, enc) in sections:
        data = pad(data, 0x200)
        enc_data = enc(pos, data, content_key) if enc else data
        fs_entries += struct.pack("<IIQ", pos // 0x200, (pos + len(data)) // 0x200, 0)
        fs_hdrs += bytes(h); body += enc_data; pos += len(data)
    fs_entries += b"\0" * (16 * (4 - len(sections))); fs_hdrs += b"\0" * (0x200 * (4 - len(sections)))
    hd = bytearray(0xC00)
    hd[0x200:0x204] = b"NCA3"; hd[0x205] = ctype; hd[0x206] = 3; hd[0x207] = 0
    hd[0x208:0x210] = u64(pos); hd[0x210:0x218] = u64(tid); hd[0x220] = 3
    if rights: hd[0x230:0x240] = rights
    hd[0x240:0x280] = fs_entries; hd[0x300:0x340] = ecb_enc(KAK, area)
    hd[0x400:0x400 + len(fs_hdrs)] = fs_hdrs
    enc_hd = b"".join(xts_enc_sector(HEADER_KEY, s, bytes(hd[s * 0x200:(s + 1) * 0x200])) for s in range(6))
    return enc_hd + body, content_key

def ctr_enc(pos, data, key, h=None): raise NotImplementedError

def sec_ctr(fs_type, hash_type, data, setup):
    h = fs_hdr(fs_type, hash_type, 3); setup(h)
    ctr8 = bytes(h[0x140:0x148])
    return (h, data, lambda pos, d, key: ctr(key, ctr_iv(ctr8, pos), d))

def sec_pfs(files):
    img = pfs0(files)
    def setup(h): h[0x40:0x48] = u64(0x1000); h[0x48:0x50] = u64(len(img))
    return sec_ctr(1, 2, b"\0" * 0x1000 + img, setup)

def sec_romfs(files):
    img = romfs(files)
    def setup(h): h[0x90:0x98] = u64(0x4000); h[0x98:0xA0] = u64(len(img))
    return sec_ctr(0, 3, b"\0" * 0x4000 + img, setup)

def bktr_tree(entries, entry_size, end):
    node = bytearray(0x4000); struct.pack_into("<IIQ", node, 0, 0, 1, end)
    node[16:24] = entries[0][:8]
    es = bytearray(0x4000); struct.pack_into("<IIQ", es, 0, 0, len(entries), end)
    for i, e in enumerate(entries): es[16 + i * entry_size:16 + (i + 1) * entry_size] = e
    return bytes(node) + bytes(es)

def sec_bktr(base_files, new_files):
    B = b"\0" * 0x4000 + romfs(base_files); N = b"\0" * 0x4000 + romfs(new_files)
    L = align(max(len(B), len(N)), 0x1000); B = pad(B, 0x1000).ljust(L, b"\0"); N = pad(N, 0x1000).ljust(L, b"\0")
    reloc = []; P = b""
    for o in range(0, L, 0x1000):
        same = B[o:o + 0x1000] == N[o:o + 0x1000]
        st = 0 if same else 1
        if reloc and reloc[-1][2] == st and (st == 0 or True):
            if st == 1: P += N[o:o + 0x1000]
            continue
        phys = o if same else len(P)
        if not same: P += N[o:o + 0x1000]
        reloc.append((o, phys, st))
    relb = [struct.pack("<QQI", v, p, s) for v, p, s in reloc]
    # subsection table: split P in pieces w/ different generations
    gens = [11, 22, 33, 44]; step = max(0x1000, align(len(P) // 3, 0x1000)); subs = []
    for i, o in enumerate(range(0, len(P), step)): subs.append((o, gens[i % 4]))
    gen_hdr = 7
    subs.append((len(P), gen_hdr))
    subb = [struct.pack("<QII", o, 0, g) for o, g in subs]
    reltree = bktr_tree(relb, 20, L); subtree = bktr_tree(subb, 16, len(P) + len(reltree) + 0x8000)
    h = fs_hdr(0, 3, 4, gen=gen_hdr); ctr8 = bytes(h[0x140:0x148])
    h[0x90:0x98] = u64(0x4000); h[0x98:0xA0] = u64(len(romfs(new_files)))
    rel_off = len(P); sub_off = rel_off + len(reltree)
    h[0x100:0x108] = u64(rel_off); h[0x108:0x110] = u64(len(reltree)); h[0x110:0x114] = b"BKTR"
    h[0x114:0x118] = struct.pack("<I", 1); h[0x118:0x11C] = struct.pack("<I", len(relb))
    h[0x120:0x128] = u64(sub_off); h[0x128:0x130] = u64(len(subtree)); h[0x130:0x134] = b"BKTR"
    h[0x134:0x138] = struct.pack("<I", 1); h[0x138:0x13C] = struct.pack("<I", len(subb))
    data = P + reltree + subtree
    def enc(pos, d, key):
        out = b""
        for i, (o, g) in enumerate(subs[:-1]):
            e = subs[i + 1][0]
            out += ctr(key, ctr_iv(ctr8, pos + o, g), d[o:e])
        out += ctr(key, ctr_iv(ctr8, pos + len(P)), d[len(P):])
        return out
    return (h, data, enc)

def tik(rights, enc_titlekey):
    t = bytearray(0x2C0); t[0:4] = struct.pack("<I", 0x10004); body = 4 + 0x100 + 0x3C
    t[body + 0x40: body + 0x50] = enc_titlekey; t[body + 0x141] = 0; t[body + 0x160: body + 0x170] = rights
    return bytes(t)

def nacp(name, ver):
    b = bytearray(0x4000); b[0:len(name)] = name.encode(); b[0x3060:0x3060 + len(ver)] = ver.encode(); return bytes(b)

def cnmt(tid, version, typ):
    b = bytearray(0x40); b[0:8] = u64(tid); b[8:12] = struct.pack("<I", version); b[0xC] = typ; return bytes(b)

# ---------------- content
APP = 0x0100ABCD00012000; UPD = APP + 0x800
base_exefs = {"main": rb(5000), "main.npdm": rb(700), "rtld": rb(300), "sdk": rb(900), "subsdk0": rb(1100)}
upd_exefs = dict(base_exefs); upd_exefs["main"] = rb(6000); upd_exefs["subsdk1"] = rb(123)
big1 = rb(3_500_000); big2 = bytearray(rb(2_300_000))
base_rom = {"data/big1.bin": big1, "data/big2.bin": bytes(big2), "data/small/a.txt": b"hello base", "readme.txt": b"base readme",
            "old.txt": b"to be deleted", "sound/s1.dat": rb(40000)}
big2b = bytearray(big2); big2b[1_000_003:1_000_040] = b"PATCHED-" * 4 + b"x"; big2b[2_000_000:2_000_010] = b"\xAA" * 10
new_rom = {"data/big1.bin": big1, "data/big2.bin": bytes(big2b), "data/small/a.txt": b"hello UPDATED text",
           "readme.txt": b"base readme", "sound/s1.dat": rb(40000), "newdir/n.txt": b"brand new file"}
rights = rb(15) + bytes([GEN]); rights_u = rb(15) + bytes([GEN])
tk = rb(16)           # content (decrypted) title key
enc_tk = ecb_enc(TITLEKEK, tk)
icon = b"\xff\xd8\xff\xe0FAKEJPEG" + rb(100)

def nca_blob(*a, **k): return make_nca(*a, **k)[0]
prog_b = nca_blob(0, APP, [sec_pfs(base_exefs), sec_romfs(base_rom)], rights=rights, content_key=tk)
ctrl_b = nca_blob(2, APP, [sec_romfs({"control.nacp": nacp("Test Game: Ünïcode", "1.0.0"), "icon_AmericanEnglish.dat": icon})], rights=rights, content_key=tk)
meta_b = nca_blob(1, APP, [sec_pfs({"Application_%016x.cnmt" % APP: cnmt(APP, 0, 0x80)})])
prog_u = nca_blob(0, UPD, [sec_pfs(upd_exefs), sec_bktr(base_rom, new_rom)], rights=rights_u, content_key=tk)
ctrl_u = nca_blob(2, UPD, [sec_romfs({"control.nacp": nacp("Test Game: Ünïcode", "1.1.0"), "icon_AmericanEnglish.dat": icon})], rights=rights_u, content_key=tk)
meta_u = nca_blob(1, UPD, [sec_pfs({"Patch_%016x.cnmt" % UPD: cnmt(UPD, 65536, 0x81)})])

out = sys.argv[1]; os.makedirs(out, exist_ok=True)
open(out + "/prod.keys", "w").write(
    "; test keys\nheader_key = %s\nkey_area_key_application_%02x = %s\ntitlekek_%02x = %s\nmaster_key_%02x = %s\n"
    % (HEADER_KEY.hex(), GEN, KAK.hex(), GEN, TITLEKEK.hex(), GEN, rb(16).hex()))
open(out + "/base.nsp", "wb").write(pfs0({"p.nca": prog_b, "c.nca": ctrl_b, "m.cnmt.nca": meta_b, "r.tik": tik(rights, enc_tk)}))
open(out + "/update.nsp", "wb").write(pfs0({"p.nca": prog_u, "c.nca": ctrl_u, "m.cnmt.nca": meta_u, "r.tik": tik(rights_u, enc_tk)}))
# XCI: pad header, HFS0 root at 0xF000
sec = hfs0({"p.nca": prog_b, "c.nca": ctrl_b, "m.cnmt.nca": meta_b, "r.tik": tik(rights, enc_tk)})
root = hfs0({"update": hfs0({}), "secure": sec})
x = bytearray(0xF000); x[0x100:0x104] = b"HEAD"; x[0x130:0x138] = u64(0xF000); x[0x138:0x140] = u64(len(root) - len(sec))
open(out + "/game.xci", "wb").write(bytes(x) + root)

def dump_expected(d, files):
    for p, b in files.items():
        fp = os.path.join(out, d, p); os.makedirs(os.path.dirname(fp), exist_ok=True); open(fp, "wb").write(b)
dump_expected("exp_base/exefs", base_exefs); dump_expected("exp_base/romfs", base_rom)
dump_expected("exp_merged/exefs", upd_exefs); dump_expected("exp_merged/romfs", new_rom)
print("generated", os.listdir(out))
