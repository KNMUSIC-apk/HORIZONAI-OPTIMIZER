package com.horizon.ai.ai;

/**
 * Circuit breaker: sau N lỗi liên tiếp, ngừng gọi AI trong một khoảng thời gian.
 * Sau khi hết thời gian, cho phép MỘT yêu cầu thử (HALF_OPEN); thành công thì đóng lại, thất bại thì mở tiếp.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openMillis;
    private State state = State.CLOSED;
    private int failures;
    private long openedAt;

    public CircuitBreaker(int failureThreshold, long openMillis) {
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
    }

    public synchronized boolean allowRequest(long now) {
        if (state == State.OPEN) {
            if (now - openedAt >= openMillis) {
                state = State.HALF_OPEN;
                return true;
            }
            return false;
        }
        return true;
    }

    public synchronized void onSuccess() {
        state = State.CLOSED;
        failures = 0;
    }

    public synchronized void onFailure(long now) {
        failures++;
        if (state == State.HALF_OPEN || failures >= failureThreshold) {
            state = State.OPEN;
            openedAt = now;
            failures = 0;
        }
    }

    public synchronized State state() {
        return state;
    }
}
