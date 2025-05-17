package id.rnggagib.raid;

import java.time.LocalDateTime;

public class CronExpression {
    
    public static boolean matches(String cronExpression, LocalDateTime dateTime) {
        String[] parts = cronExpression.trim().split("\\s+");
        if (parts.length != 5) {
            return false;
        }
        
        int minute = dateTime.getMinute();
        int hour = dateTime.getHour();
        int dayOfMonth = dateTime.getDayOfMonth();
        int month = dateTime.getMonthValue();
        int dayOfWeek = dateTime.getDayOfWeek().getValue() % 7; // 0 = Sunday, 6 = Saturday
        
        return matches(parts[0], minute) &&
               matches(parts[1], hour) &&
               matches(parts[2], dayOfMonth) &&
               matches(parts[3], month) &&
               matches(parts[4], dayOfWeek);
    }
    
    private static boolean matches(String pattern, int value) {
        if (pattern.equals("*")) {
            return true;
        }
        
        if (pattern.contains(",")) {
            String[] values = pattern.split(",");
            for (String val : values) {
                if (matches(val, value)) {
                    return true;
                }
            }
            return false;
        }
        
        if (pattern.contains("-")) {
            String[] range = pattern.split("-");
            int start = Integer.parseInt(range[0]);
            int end = Integer.parseInt(range[1]);
            return value >= start && value <= end;
        }
        
        if (pattern.contains("/")) {
            String[] parts = pattern.split("/");
            int divisor = Integer.parseInt(parts[1]);
            return value % divisor == 0; // Value is divisible by the divisor
        }
        
        return Integer.parseInt(pattern) == value;
    }
}