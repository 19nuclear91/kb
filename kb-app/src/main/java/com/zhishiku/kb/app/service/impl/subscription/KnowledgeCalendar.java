package com.zhishiku.kb.app.service.impl.subscription;

import com.zhishiku.kb.app.service.subscription.SubscriptionFailure;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** 固定北京时间业务周，不读取本机时区或其他功能的可变时区配置。 */
public final class KnowledgeCalendar {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private KnowledgeCalendar() {
    }

    public static LocalDate week(Instant time) {
        return time.atZone(ZONE).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public static Instant due(LocalDate week) {
        requireMonday(week);
        return week.plusWeeks(1).atTime(9, 0).atZone(ZONE).toInstant();
    }

    public static LocalDate parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw SubscriptionFailure.invalid();
            }
            LocalDate week = LocalDate.parse(text);
            requireMonday(week);
            return week;
        } catch (RuntimeException ignored) {
            throw SubscriptionFailure.invalid();
        }
    }

    private static void requireMonday(LocalDate week) {
        if (week == null || week.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw SubscriptionFailure.invalid();
        }
    }
}
