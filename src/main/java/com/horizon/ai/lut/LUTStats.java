package com.horizon.ai.lut;

import java.util.Arrays;

/**
 * Hàm thống kê thuần Java, an toàn với NaN và Infinity.
 * Mọi hàm chỉ đọc mảng, không cấp phát trong vòng lặp nóng (trừ median với n nhỏ, tối đa 120).
 * Không dùng Bukkit: có thể chạy trong bất kỳ luồng nào trên snapshot đã sao chép.
 */
public final class LUTStats {

    private LUTStats() {
    }

    /** Trả về v nếu hữu hạn, ngược lại trả về def. */
    public static double finiteOr(double v, double def) {
        return Double.isFinite(v) ? v : def;
    }

    /** Kẹp v vào [min, max]. NaN trả về min. */
    public static double clamp(double v, double min, double max) {
        if (Double.isNaN(v)) {
            return min;
        }
        return Math.max(min, Math.min(max, v));
    }

    /** Trung bình các giá trị hữu hạn trong [from, to). 0 nếu không có mẫu. */
    public static double mean(double[] a, int from, int to) {
        double sum = 0;
        int count = 0;
        for (int i = from; i < to; i++) {
            if (Double.isFinite(a[i])) {
                sum += a[i];
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    /** Hệ số biến thiên (độ lệch chuẩn / trung bình) trên [from, to). 0 nếu trung bình không dương. */
    public static double coefficientOfVariation(double[] a, int from, int to) {
        double m = mean(a, from, to);
        if (m <= 0) {
            return 0.0;
        }
        double ss = 0;
        int count = 0;
        for (int i = from; i < to; i++) {
            if (Double.isFinite(a[i])) {
                double d = a[i] - m;
                ss += d * d;
                count++;
            }
        }
        return count < 2 ? 0.0 : Math.sqrt(ss / count) / m;
    }

    /** Hệ số góc hồi quy tuyến tính theo chỉ số mẫu (đơn vị: giá trị trên mỗi cửa sổ). */
    public static double slope(double[] a, int from, int to) {
        int n = to - from;
        if (n < 2) {
            return 0.0;
        }
        double mean = mean(a, from, to);
        double mx = (n - 1) / 2.0;
        double sxy = 0;
        double sxx = 0;
        for (int i = 0; i < n; i++) {
            double v = a[from + i];
            if (!Double.isFinite(v)) {
                v = mean;
            }
            double dx = i - mx;
            sxy += dx * (v - mean);
            sxx += dx * dx;
        }
        return sxx == 0 ? 0.0 : sxy / sxx;
    }

    /** Trung vị các giá trị hữu hạn trong [from, to). 0 nếu không có mẫu. */
    public static double median(double[] a, int from, int to) {
        int n = 0;
        double[] tmp = new double[Math.max(0, to - from)];
        for (int i = from; i < to; i++) {
            if (Double.isFinite(a[i])) {
                tmp[n++] = a[i];
            }
        }
        if (n == 0) {
            return 0.0;
        }
        Arrays.sort(tmp, 0, n);
        return n % 2 == 1 ? tmp[n / 2] : (tmp[n / 2 - 1] + tmp[n / 2]) / 2.0;
    }

    /** Max các giá trị hữu hạn trong [from, to). 0 nếu không có mẫu. */
    public static double max(double[] a, int from, int to) {
        double best = Double.NEGATIVE_INFINITY;
        for (int i = from; i < to; i++) {
            if (Double.isFinite(a[i]) && a[i] > best) {
                best = a[i];
            }
        }
        return Double.isInfinite(best) ? 0.0 : best;
    }

    /**
     * Hệ số Pearson trên các cặp (x[i], y[i]) với i trong [from, to), bỏ qua cặp chứa giá trị không hữu hạn.
     * Trả về NaN khi có ít hơn 2 cặp hợp lệ hoặc phương sai bằng 0 (không thể kết luận tương quan).
     */
    public static double pearson(double[] x, double[] y, int from, int to) {
        int n = 0;
        double mx = 0;
        double my = 0;
        for (int i = from; i < to; i++) {
            if (Double.isFinite(x[i]) && Double.isFinite(y[i])) {
                mx += x[i];
                my += y[i];
                n++;
            }
        }
        if (n < 2) {
            return Double.NaN;
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = from; i < to; i++) {
            if (Double.isFinite(x[i]) && Double.isFinite(y[i])) {
                double dx = x[i] - mx;
                double dy = y[i] - my;
                sxy += dx * dy;
                sxx += dx * dx;
                syy += dy * dy;
            }
        }
        if (sxx <= 0 || syy <= 0) {
            return Double.NaN;
        }
        double r = sxy / Math.sqrt(sxx * syy);
        return Double.isFinite(r) ? clamp(r, -1.0, 1.0) : Double.NaN;
    }
}
