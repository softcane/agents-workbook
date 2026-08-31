package io.github.softcane.workbook.proxy;

import java.util.List;
import java.util.Map;

public interface DownstreamSink {
    void headers(int status, Map<String, List<String>> headers);
    void body(byte[] bytes, boolean endOfStream);
    void failure(String code, String message);
}
