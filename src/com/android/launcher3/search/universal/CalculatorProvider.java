package com.android.launcher3.search.universal;

import android.content.Context;
import android.content.Intent;
import android.os.Process;

import com.android.launcher3.LauncherPrefs;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CalculatorProvider implements SearchProvider {

    private static final Pattern CONVERSION = Pattern.compile(
            "^\\s*(-?[0-9.]+)\\s*([a-z°]+)\\s+(?:in|to)\\s+([a-z°]+)\\s*$");
    private static final DecimalFormat FORMAT = new DecimalFormat("#,##0.######");

    private static final Map<String, Double> LENGTH = Map.ofEntries(
            Map.entry("mm", 0.001), Map.entry("cm", 0.01), Map.entry("m", 1.0),
            Map.entry("km", 1000.0), Map.entry("in", 0.0254), Map.entry("inch", 0.0254),
            Map.entry("ft", 0.3048), Map.entry("feet", 0.3048), Map.entry("yd", 0.9144),
            Map.entry("mi", 1609.344), Map.entry("mile", 1609.344),
            Map.entry("miles", 1609.344), Map.entry("nmi", 1852.0));

    private static final Map<String, Double> MASS = Map.of(
            "mg", 0.000001, "g", 0.001, "kg", 1.0, "t", 1000.0,
            "oz", 0.028349523125, "lb", 0.45359237, "lbs", 0.45359237, "st", 6.35029318);

    private static final Map<String, Double> DATA = Map.of(
            "b", 1.0, "kb", 1024.0, "mb", 1048576.0, "gb", 1073741824.0,
            "tb", 1099511627776.0);

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_CALCULATOR;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_CALCULATOR.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return out;
        }
        String answer = convert(trimmed.toLowerCase(Locale.ROOT));
        if (answer == null) {
            answer = evaluate(trimmed);
        }
        if (answer == null) {
            return out;
        }
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.APP_CALCULATOR")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        UniversalSearchResult result = new UniversalSearchResult(
                UniversalSearchResult.SOURCE_CALCULATOR, "calc", answer, trimmed,
                intent, Process.myUserHandle(), 1000);
        out.add(result);
        return out;
    }

    private static String convert(String query) {
        Matcher m = CONVERSION.matcher(query);
        if (!m.matches()) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String from = m.group(2);
        String to = m.group(3);
        Double result = temperature(value, from, to);
        if (result == null) {
            result = scale(value, from, to, LENGTH);
        }
        if (result == null) {
            result = scale(value, from, to, MASS);
        }
        if (result == null) {
            result = scale(value, from, to, DATA);
        }
        return result == null ? null : FORMAT.format(result) + " " + to;
    }

    private static Double scale(double value, String from, String to, Map<String, Double> units) {
        Double a = units.get(from);
        Double b = units.get(to);
        return a == null || b == null ? null : value * a / b;
    }

    private static Double temperature(double value, String from, String to) {
        double celsius;
        switch (from) {
            case "c": case "°c": case "celsius": celsius = value; break;
            case "f": case "°f": case "fahrenheit": celsius = (value - 32) * 5 / 9; break;
            case "k": case "kelvin": celsius = value - 273.15; break;
            default: return null;
        }
        switch (to) {
            case "c": case "°c": case "celsius": return celsius;
            case "f": case "°f": case "fahrenheit": return celsius * 9 / 5 + 32;
            case "k": case "kelvin": return celsius + 273.15;
            default: return null;
        }
    }

    private static String evaluate(String expression) {
        if (!expression.matches("^[-+*/%^(). 0-9]+$") || !expression.matches(".*[-+*/%^].*")) {
            return null;
        }
        try {
            Parser parser = new Parser(expression.replace(" ", ""));
            double value = parser.expression();
            if (!parser.done() || Double.isNaN(value) || Double.isInfinite(value)) {
                return null;
            }
            return FORMAT.format(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static class Parser {
        private final String mText;
        private int mPos;

        Parser(String text) {
            mText = text;
        }

        boolean done() {
            return mPos >= mText.length();
        }

        double expression() {
            double value = term();
            while (!done() && (peek() == '+' || peek() == '-')) {
                char op = mText.charAt(mPos++);
                double rhs = term();
                value = op == '+' ? value + rhs : value - rhs;
            }
            return value;
        }

        private double term() {
            double value = power();
            while (!done() && (peek() == '*' || peek() == '/' || peek() == '%')) {
                char op = mText.charAt(mPos++);
                double rhs = power();
                if (op == '*') {
                    value *= rhs;
                } else if (op == '/') {
                    value /= rhs;
                } else {
                    value %= rhs;
                }
            }
            return value;
        }

        private double power() {
            double value = unary();
            if (!done() && peek() == '^') {
                mPos++;
                return Math.pow(value, power());
            }
            return value;
        }

        private double unary() {
            if (!done() && peek() == '-') {
                mPos++;
                return -unary();
            }
            if (!done() && peek() == '+') {
                mPos++;
                return unary();
            }
            return atom();
        }

        private double atom() {
            if (done()) {
                throw new IllegalStateException();
            }
            if (peek() == '(') {
                mPos++;
                double value = expression();
                if (done() || peek() != ')') {
                    throw new IllegalStateException();
                }
                mPos++;
                return value;
            }
            int start = mPos;
            while (!done() && (Character.isDigit(peek()) || peek() == '.')) {
                mPos++;
            }
            if (start == mPos) {
                throw new IllegalStateException();
            }
            return Double.parseDouble(mText.substring(start, mPos));
        }

        private char peek() {
            return mText.charAt(mPos);
        }
    }
}
