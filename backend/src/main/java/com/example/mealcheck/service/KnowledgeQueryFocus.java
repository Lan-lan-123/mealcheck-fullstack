package com.example.mealcheck.service;

import com.example.mealcheck.dto.KnowledgeSnippet;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded lexical features shared by hybrid retrieval and local scenario reranking. */
final class KnowledgeQueryFocus {
    static final String VERSION="scenario-focus-v1";
    private static final Pattern RUN=Pattern.compile("[\\p{IsHan}]+|[a-zA-Z][a-zA-Z0-9-]*|[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern FILLER=Pattern.compile("是不是|可不可以|应不应该|应该|怎样|怎么|如何|哪些|什么|能不能|可以|需要|是否|一下|一个|这个|这样|那样|请问|帮我|一般|通常");
    private static final Set<String> STOP=Set.of("should","could","would","what","which","this","that","with","have","does","please","the","and","for","怎么","怎样","应该","可以","一下","是不是");
    private KnowledgeQueryFocus() { }
    static List<String> terms(String query) {
        String clean=FILLER.matcher(Objects.toString(query,"").toLowerCase(Locale.ROOT)).replaceAll(" ");
        var groups=new ArrayList<List<String>>();var matcher=RUN.matcher(clean);
        while(matcher.find()) {
            String word=matcher.group();var features=new LinkedHashSet<String>();
            if(Character.UnicodeScript.of(word.codePointAt(0))==Character.UnicodeScript.HAN) {
                int[] points=word.codePoints().toArray();
                // Alternate the head/tail and feature lengths: a long sentence must not lose its final action/condition.
                for(int offset=0;offset<points.length;offset++) {
                    int i=offset%2==0?offset/2:points.length-1-offset/2;
                    for(int n=2;n<=4;n++)if(i+n<=points.length)features.add(new String(points,i,n));
                }
                if(points.length==1)features.add(word);
            } else if(word.length()>=2)features.add(word);
            features.removeIf(term->STOP.contains(term) || term.isBlank());groups.add(new ArrayList<>(features));
        }
        var selected=new LinkedHashSet<String>();
        for(int index=0;selected.size()<32;index++) {
            boolean added=false;
            for(var group:groups)if(index<group.size()){selected.add(group.get(index));added=true;if(selected.size()==32)break;}
            if(!added)break;
        }
        return List.copyOf(selected);
    }
    static String supplementQuery(String original,String hint) {
        String base=Objects.toString(original,"").strip();
        String focus=String.join(" ",terms(hint==null || hint.isBlank()?base:hint));
        if(focus.isBlank())return "";
        // Keep the complete original intent; extra search terms cannot replace it.
        int available=500-base.length()-1;
        if(available<2)return "";
        if(focus.length()>available) {
            int boundary=focus.lastIndexOf(' ',available);
            if(boundary<1)return "";focus=focus.substring(0,boundary);
        }
        return base+" "+focus;
    }
    static String normalizedQuery(String query) {
        return Objects.toString(query,"").toLowerCase(Locale.ROOT).replaceAll("[\\s，。！？?！]+"," ").strip();
    }
    static List<KnowledgeSnippet> rerank(String query,List<KnowledgeSnippet> candidates,int rrfK) {
        var terms=terms(query);if(terms.isEmpty() || candidates.size()<2)return candidates;
        var frequencies=new HashMap<String,Integer>();
        for(String term:terms)for(var candidate:candidates)
            if(searchText(candidate).contains(term))frequencies.merge(term,1,Integer::sum);
        var local=new HashMap<Integer,Double>();
        for(int index=0;index<candidates.size();index++) {
            var candidate=candidates.get(index);String title=Objects.toString(candidate.getTitle(),"").toLowerCase(Locale.ROOT);
            String body=body(candidate);double score=0;
            for(String term:terms) {
                double rarity=Math.log1p((double)(candidates.size()+1)/(frequencies.getOrDefault(term,0)+1));
                if(title.contains(term))score+=3.0*rarity;
                else if(body.contains(term))score+=rarity;
            }
            local.put(index,score);
        }
        if(local.values().stream().noneMatch(value->value>0))return candidates;
        var order=new ArrayList<Integer>();for(int i=0;i<candidates.size();i++)order.add(i);
        order.sort(Comparator.<Integer>comparingDouble(local::get).reversed().thenComparingInt(Integer::intValue));
        var ranks=new HashMap<Integer,Integer>();for(int i=0;i<order.size();i++)ranks.put(order.get(i),i+1);
        int k=Math.max(1,Math.min(1000,rrfK));var result=new ArrayList<KnowledgeSnippet>();
        for(int i=0;i<candidates.size();i++) {
            var source=candidates.get(i);
            // Apply a bounded rank-ratio adjustment, retaining the original score magnitude so
            // a weak candidate does not become "high confidence" merely by having a local rank.
            double adjustment=0.8+0.2*(k+i+1.0)/(k+ranks.get(i));
            double score=Double.isFinite(source.getScore())?Math.max(0,Math.min(1,source.getScore()*adjustment)):0;
            var copy=new KnowledgeSnippet(source.getId(),source.getTitle(),source.getContent(),source.getCategory(),score);
            copy.setRoughScore(source.getRoughScore());copy.setRoughRank(source.getRoughRank());
            copy.setRerankerRawScore(source.getRerankerRawScore());copy.setRerankerRank(source.getRerankerRank());copy.setReranked(source.isReranked());
            result.add(copy);
        }
        result.sort(Comparator.comparingDouble(KnowledgeSnippet::getScore).reversed());return List.copyOf(result);
    }
    private static String body(KnowledgeSnippet source) {
        String text=Objects.toString(source.getContent(),"");int footer=text.indexOf("参考：");
        return (footer<0?text:text.substring(0,footer)).toLowerCase(Locale.ROOT);
    }
    private static String searchText(KnowledgeSnippet source){return Objects.toString(source.getTitle(),"").toLowerCase(Locale.ROOT)+" "+body(source);}
}
