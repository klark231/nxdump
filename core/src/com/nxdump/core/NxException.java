package com.nxdump.core;

import java.io.IOException;

/** Any user-presentable failure (bad key, unsupported format, ...). */
public class NxException extends IOException {
    public NxException(String m) { super(m); }
    public NxException(String m, Throwable t) { super(m, t); }
}
