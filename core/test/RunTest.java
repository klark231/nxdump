import com.nxdump.core.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;

public class RunTest {
    static Container open(String p) throws IOException {
        FileChannel ch = FileChannel.open(Paths.get(p));
        return Container.open(new Source.OfChannel(ch, null), Paths.get(p).getFileName().toString(), ch);
    }
    static Dumper.Sink sink(Path root) {
        return rel -> { Path f = root.resolve(rel); Files.createDirectories(f.getParent()); return Files.newOutputStream(f); };
    }
    public static void main(String[] a) throws Exception {
        String dir = a[0];
        Keys keys = Keys.load(new FileInputStream(dir + "/prod.keys"), null);
        System.out.println("keys=" + keys.count() + " master=" + keys.maxMasterKey() + " header=" + keys.hasHeaderKey());
        for (String f : new String[]{"base.nsp", "update.nsp", "game.xci"}) {
            try (Container c = open(dir + "/" + f)) {
                for (Analyzer.Info i : Analyzer.analyze(c, keys))
                    System.out.printf("%s: kind=%d tid=%016x app=%016x ver=%d name='%s' dispVer=%s icon=%s err=%s%n",
                            f, i.kind, i.titleId, i.appId, i.version, i.name, i.displayVersion, i.icon == null ? "-" : i.icon.length, i.error);
            }
        }
        Dumper.Progress pr = new Dumper.Progress() {
            long last; public void update(String f, long d, long t) { last = d; }
            public boolean cancelled() { return false; } };
        long app = 0x0100ABCD00012000L;
        try (Container b = open(dir + "/base.nsp")) {
            Dumper.dump(b, null, app, true, true, keys, sink(Paths.get(dir, "out_base")), pr);
        }
        try (Container b = open(dir + "/base.nsp"); Container u = open(dir + "/update.nsp")) {
            Dumper.dump(b, u, app, true, true, keys, sink(Paths.get(dir, "out_merged")), pr);
        }
        try (Container x = open(dir + "/game.xci")) {
            Dumper.dump(x, null, app, true, true, keys, sink(Paths.get(dir, "out_xci")), pr);
        }
        System.out.println("done");
    }
}
