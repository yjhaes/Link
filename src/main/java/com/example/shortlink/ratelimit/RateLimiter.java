package com.example.shortlink.ratelimit;

/** The single admission boundary; uncertainty is distinct from an exhausted bucket. */
public interface RateLimiter {
    Decision admitCreate(String peerAddress);

    default Decision admitRedirect(String peerAddress) { return Decision.unavailable(); }
    default Decision admitManagementWrite() { return Decision.unavailable(); }
    default Decision admitManagementQuery() { return Decision.unavailable(); }

    record Decision(Status status, long waitMillis) {
        public enum Status { ALLOWED, REJECTED, UNAVAILABLE }

        public Decision {
            if (status == null
                    || waitMillis < 0
                    || (status == Status.REJECTED && waitMillis == 0)) {
                throw new IllegalArgumentException("Invalid admission decision.");
            }
        }

        public static Decision allowed() {
            return new Decision(Status.ALLOWED, 0);
        }

        public static Decision rejected(long waitMillis) {
            return new Decision(Status.REJECTED, waitMillis);
        }

        public static Decision unavailable() {
            return new Decision(Status.UNAVAILABLE, 0);
        }
    }
}
