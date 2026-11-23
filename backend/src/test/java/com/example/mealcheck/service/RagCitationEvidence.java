package com.example.mealcheck.service;

/** Checks source identity/excerpt fidelity, not whether the source supports an answer. */
final class RagCitationEvidence {
    private RagCitationEvidence() { }
    static boolean textMatches(String source,String excerpt) {
        if(source==null || excerpt==null || source.isBlank() || excerpt.isBlank()) return false;
        String full=source.replaceAll("\\s+"," ").trim();
        String shown=excerpt.replaceAll("\\s+"," ").trim();
        if(full.equals(shown)) return true;
        return shown.endsWith("...") && shown.length()>3 && full.startsWith(shown.substring(0,shown.length()-3));
    }
}
