package com.example.mealcheck.service;

import java.util.*;
import java.util.stream.IntStream;

final class RagEvaluationSample {
    private RagEvaluationSample() { }
    static List<Integer> select(int total,int count,long seed) {
        return selectFrom(IntStream.rangeClosed(1,total).boxed().toList(),count,seed);
    }
    static List<Integer> selectFrom(List<Integer> population,int count,long seed) {
        if(count<1 || count>population.size()) throw new IllegalArgumentException("抽样数量必须在 1 到题目总数之间。");
        var ids=new ArrayList<>(population);
        Collections.shuffle(ids,new Random(seed));
        return ids.subList(0,count).stream().sorted().toList();
    }
    static List<Integer> excluding(List<Integer> population,String csv,int total) {
        if(csv==null || csv.isBlank())return List.copyOf(population);
        var excluded=new HashSet<Integer>();
        for(String item:csv.split(",",-1)) {
            int id=Integer.parseInt(item.strip());
            if(id<1 || id>total)throw new IllegalArgumentException("Excluded case ID out of range: "+id);
            excluded.add(id);
        }
        return population.stream().filter(id->!excluded.contains(id)).toList();
    }
}
