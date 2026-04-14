package io.github.rladmstj.esmserver.dto;

import java.util.List;

public class AlarmBatchRequest {
    public String userId;
    public String mode; // "random" or "periodic"
    public List<AlarmWindow> windows;

    public static class AlarmWindow {
        public long start; // timestamp (ms)
        public long end;
    }
}
