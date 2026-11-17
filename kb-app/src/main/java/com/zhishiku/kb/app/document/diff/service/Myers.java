package com.zhishiku.kb.app.document.diff.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 双向搜索中点并分治；不保存逐层轨迹，额外空间为线性。 */
final class Myers {
    record Edit(String type, int before, int after) {
    }

    static List<Edit> diff(List<?> before, List<?> after, DiffDeadline deadline) {
        List<Edit> result = new ArrayList<>();
        split(before, 0, before.size(), after, 0, after.size(), deadline, result);
        return result;
    }

    private static void split(List<?> a, int fromA, int toA, List<?> b, int fromB, int toB,
                              DiffDeadline deadline, List<Edit> out) {
        deadline.check();
        while (fromA < toA && fromB < toB && Objects.equals(a.get(fromA), b.get(fromB))) {
            deadline.check();
            out.add(new Edit("EQUAL", fromA++, fromB++));
        }
        int suffix = 0;
        while (fromA < toA - suffix && fromB < toB - suffix
                && Objects.equals(a.get(toA - suffix - 1), b.get(toB - suffix - 1))) {
            deadline.check();
            suffix++;
        }
        int endA = toA - suffix;
        int endB = toB - suffix;
        if (fromA == endA || fromB == endB) {
            appendChanged(fromA, endA, fromB, endB, out, deadline);
        } else {
            int[] middle = midpoint(a, fromA, endA, b, fromB, endB, deadline);
            if (middle == null || (middle[0] == fromA && middle[1] == fromB)
                    || (middle[0] == endA && middle[1] == endB)) {
                appendChanged(fromA, endA, fromB, endB, out, deadline);
            } else {
                split(a, fromA, middle[0], b, fromB, middle[1], deadline, out);
                split(a, middle[0], endA, b, middle[1], endB, deadline, out);
            }
        }
        for (int i = 0; i < suffix; i++) {
            deadline.check();
            out.add(new Edit("EQUAL", endA + i, endB + i));
        }
    }

    private static void appendChanged(int fromA, int toA, int fromB, int toB,
                                      List<Edit> out, DiffDeadline deadline) {
        for (int i = fromA; i < toA; i++) {
            deadline.check();
            out.add(new Edit("DELETE", i, -1));
        }
        for (int j = fromB; j < toB; j++) {
            deadline.check();
            out.add(new Edit("INSERT", -1, j));
        }
    }

    private static int[] midpoint(List<?> a, int fromA, int toA, List<?> b, int fromB, int toB,
                                   DiffDeadline deadline) {
        int n = toA - fromA;
        int m = toB - fromB;
        int maximum = (n + m + 1) / 2;
        int offset = maximum + 1;
        int[] forward = new int[2 * maximum + 3];
        int[] reverse = new int[forward.length];
        Arrays.fill(forward, -1);
        Arrays.fill(reverse, -1);
        forward[offset + 1] = 0;
        reverse[offset + 1] = 0;
        int delta = n - m;
        boolean odd = (delta & 1) != 0;
        for (int d = 0; d <= maximum; d++) {
            for (int k = -d; k <= d; k += 2) {
                deadline.check();
                int slot = offset + k;
                int x = k == -d || (k != d && forward[slot - 1] < forward[slot + 1])
                        ? forward[slot + 1] : forward[slot - 1] + 1;
                int y = x - k;
                while (x >= 0 && y >= 0 && x < n && y < m
                        && Objects.equals(a.get(fromA + x), b.get(fromB + y))) {
                    deadline.check();
                    x++;
                    y++;
                }
                forward[slot] = x;
                int opposite = offset + delta - k;
                if (odd && opposite >= 0 && opposite < reverse.length && reverse[opposite] >= 0
                        && x + reverse[opposite] >= n) {
                    return new int[]{fromA + x, fromB + y};
                }
            }
            for (int k = -d; k <= d; k += 2) {
                deadline.check();
                int slot = offset + k;
                int x = k == -d || (k != d && reverse[slot - 1] < reverse[slot + 1])
                        ? reverse[slot + 1] : reverse[slot - 1] + 1;
                int y = x - k;
                while (x >= 0 && y >= 0 && x < n && y < m
                        && Objects.equals(a.get(toA - x - 1), b.get(toB - y - 1))) {
                    deadline.check();
                    x++;
                    y++;
                }
                reverse[slot] = x;
                int opposite = offset + delta - k;
                if (!odd && opposite >= 0 && opposite < forward.length && forward[opposite] >= 0
                        && forward[opposite] + x >= n) {
                    int firstX = forward[opposite];
                    return new int[]{fromA + firstX, fromB + firstX - (delta - k)};
                }
            }
        }
        return null;
    }
}
