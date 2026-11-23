package com.example.mealcheck.service;

import java.util.*;
import java.util.regex.Pattern;

/** Source-local passage IDs retain exact original spans, including punctuation. */
final class AssistantEvidencePassages {
    private static final Pattern SENTENCE=Pattern.compile("[^。！？\\r\\n]+[。！？]?");
    record Passage(int id,String text,int start,int end) { }
    private AssistantEvidencePassages() { }
    static List<Passage> of(String content) {
        String source=Objects.toString(content,"");var result=new ArrayList<Passage>();var matcher=SENTENCE.matcher(source);
        while(matcher.find()) {
            int start=matcher.start(),end=matcher.end();
            while(start<end && Character.isWhitespace(source.charAt(start)))start++;
            while(end>start && Character.isWhitespace(source.charAt(end-1)))end--;
            String text=source.substring(start,end);
            if(text.startsWith("参考：") || text.startsWith("参考:"))break;
            if(!text.isBlank())result.add(new Passage(result.size(),text,start,end));
        }
        return List.copyOf(result);
    }
    static String exactQuote(String source,String quotation) {
        var original=canonical(Objects.toString(source,""));var quote=canonical(Objects.toString(quotation,""));
        if(quote.text().length()<4)return null;
        int found=original.text().indexOf(quote.text());if(found<0)return null;
        return source.substring(original.positions().get(found),original.positions().get(found+quote.text().length()-1)+1);
    }
    private record Canonical(String text,List<Integer> positions) { }
    private static Canonical canonical(String input) {
        var text=new StringBuilder();var positions=new ArrayList<Integer>();
        for(int i=0;i<input.length();i++) {
            char c=input.charAt(i);
            if(Character.isWhitespace(c)) {
                int next=i+1;while(next<input.length() && Character.isWhitespace(input.charAt(next)))next++;
                if(text.isEmpty() || next==input.length()){i=next-1;continue;}
                char previous=text.charAt(text.length()-1);
                if((cjk(previous) || "。！？；，、：".indexOf(previous)>=0) && cjk(input.charAt(next))) {i=next-1;continue;}
                text.append(' ');positions.add(i);i=next-1;
            } else {text.append(c=='％'?'%':c);positions.add(i);}
        }
        return new Canonical(text.toString(),positions);
    }
    private static boolean cjk(char c){return c>='\u3400' && c<='\u9fff';}
}
