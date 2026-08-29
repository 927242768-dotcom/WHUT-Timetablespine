package com.whut.timetable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class ScheduleUtils {
    private ScheduleUtils() {}

    static final class Occurrence {
        final String title;
        final String room;
        final String teacher;
        final String startTime;
        final String endTime;
        final long startMillis;
        final String key;

        Occurrence(String title, String room, String teacher, String startTime,
                   String endTime, long startMillis, String key) {
            this.title = title;
            this.room = room;
            this.teacher = teacher;
            this.startTime = startTime;
            this.endTime = endTime;
            this.startMillis = startMillis;
            this.key = key;
        }
    }

    static List<Occurrence> parseOccurrences(String json) {
        List<Occurrence> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray schedules = root.optJSONArray("weekSchedules");
            if (schedules == null) return out;
            for (int i = 0; i < schedules.length(); i++) {
                JSONObject schedule = schedules.optJSONObject(i);
                if (schedule == null) continue;
                JSONObject week = schedule.optJSONObject("week");
                String weekStart = firstNonEmpty(week == null ? "" : week.optString("startDate"));
                Date startDate = parseDate(weekStart);
                if (startDate == null) continue;
                JSONArray items = schedule.optJSONArray("items");
                if (items == null) continue;
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.optJSONObject(j);
                    if (item == null || item.optBoolean("_hidden", false)) continue;
                    int dayOfWeek = item.optInt("dayOfWeek", 0);
                    if (dayOfWeek < 1 || dayOfWeek > 7) continue;
                    String begin = firstNonEmpty(item.optString("beginTime"));
                    if (begin.isEmpty()) continue;
                    long startMillis = combineDateTime(startDate, dayOfWeek - 1, begin);
                    if (startMillis <= 0) continue;
                    String title = firstNonEmpty(
                            item.optString("courseName"), item.optString("kcmc"),
                            item.optString("name"), "课程提醒"
                    );
                    String room = firstNonEmpty(
                            item.optString("classroomName"), item.optString("classRoomName"),
                            item.optString("classPlace"), item.optString("teachingPlace"),
                            item.optString("placeName"), item.optString("roomName"),
                            item.optString("classLocation"), item.optString("place")
                    );
                    String teacher = firstNonEmpty(
                            item.optString("teacherName"), item.optString("teacher"),
                            item.optString("instructorName"), item.optString("jsxm")
                    );
                    String end = firstNonEmpty(item.optString("endTime"));
                    String code = firstNonEmpty(item.optString("courseCode"), item.optString("courseNo"), item.optString("kch"));
                    String key = weekStart + "|" + dayOfWeek + "|" + begin + "|" + title + "|" + code;
                    out.add(new Occurrence(title, room, teacher, begin, end, startMillis, key));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static Occurrence nextTodayOccurrence(String json) {
        List<Occurrence> all = parseOccurrences(json);
        Calendar now = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        end.set(Calendar.HOUR_OF_DAY, 23);
        end.set(Calendar.MINUTE, 59);
        end.set(Calendar.SECOND, 59);
        Occurrence firstToday = null;
        Occurrence next = null;
        for (Occurrence occurrence : all) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(occurrence.startMillis);
            if (sameDay(c, now)) {
                if (firstToday == null || occurrence.startMillis < firstToday.startMillis) firstToday = occurrence;
                if (occurrence.startMillis >= now.getTimeInMillis()
                        && occurrence.startMillis <= end.getTimeInMillis()
                        && (next == null || occurrence.startMillis < next.startMillis)) {
                    next = occurrence;
                }
            }
        }
        return next != null ? next : firstToday;
    }

    static int todayCount(String json) {
        List<Occurrence> all = parseOccurrences(json);
        Calendar now = Calendar.getInstance();
        int count = 0;
        for (Occurrence occurrence : all) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(occurrence.startMillis);
            if (sameDay(c, now)) count++;
        }
        return count;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static long combineDateTime(Date weekStart, int plusDays, String time) {
        try {
            String[] parts = time.split(":");
            if (parts.length < 2) return -1;
            Calendar c = Calendar.getInstance();
            c.setTime(weekStart);
            c.add(Calendar.DAY_OF_MONTH, plusDays);
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
            c.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    private static Date parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        String[] patterns = {"yyyy-MM-dd", "yyyy/MM/dd", "yyyyMMdd", "yyyy-MM-dd HH:mm:ss"};
        for (String pattern : patterns) {
            try {
                return new SimpleDateFormat(pattern, Locale.CHINA).parse(normalized);
            } catch (ParseException ignored) {
            }
        }
        return null;
    }

    static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim())) {
                return value.trim();
            }
        }
        return "";
    }
}
