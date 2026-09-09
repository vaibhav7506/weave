package com.vaibhav.weave.crdt;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import com.vaibhav.weave.crdt.HybridLogicalClock.Timestamp;
/** Dependency-free executable unit suite. Assertions always run, regardless of -ea. */
public final class CoreTest {
 static UUID A=UUID.fromString("00000000-0000-0000-0000-000000000001"), B=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), BOARD=new UUID(0,10), E=new UUID(0,20);
 static int assertions;
 static void equal(Object expected,Object actual){assertions++;if(!Objects.equals(expected,actual))throw new AssertionError("Expected "+expected+" but got "+actual);}
 static Timestamp t(long p,int l,UUID r){return new Timestamp(p,l,r);}
 static Operation create(){return new Operation(new UUID(0,1),BOARD,E,t(10,0,A),Operation.Type.ELEMENT_CREATED,Element.Kind.RECTANGLE,Map.of("x",0,"color","blue"),null,null);}
 static Operation update(long id,String field,Object value,Timestamp hlc){return new Operation(new UUID(0,id),BOARD,E,hlc,Operation.Type.FIELD_UPDATED,null,Map.of(),field,value);}
 static Operation remove(){return new Operation(new UUID(0,99),BOARD,E,t(5,0,A),Operation.Type.ELEMENT_REMOVED,null,Map.of(),null,null);}
 static OperationApplier project(List<Operation> ops){var p=new OperationApplier(BOARD);ops.forEach(p::apply);return p;}
 static void permutations(List<Operation> ops,int index,List<Element> expected){if(index==ops.size()){equal(expected,project(ops).snapshot());return;}for(int i=index;i<ops.size();i++){Collections.swap(ops,index,i);permutations(ops,index+1,expected);Collections.swap(ops,index,i);}}
 public static void main(String[] args){
  var now=new AtomicLong(100);var clock=new HybridLogicalClock(A,now::get);
  equal(t(100,0,A),clock.tick());equal(t(100,1,A),clock.tick());now.set(50);equal(t(100,2,A),clock.tick());
  equal(t(100,9,A),clock.merge(t(100,8,B)));equal(t(200,4,A),clock.merge(t(200,3,B)));equal(t(200,5,A),clock.merge(t(150,99,B)));now.set(300);equal(t(300,0,A),clock.merge(t(250,99,B)));
  equal(true,t(1,0,B).compareTo(t(1,0,A))>0);
  var newer=new FieldRegister<>("new",t(20,0,A));var older=new FieldRegister<>("old",t(10,0,A));equal(newer,newer.mergeWith(older));equal(newer,older.mergeWith(newer));
  var ops=new ArrayList<>(List.of(create(),update(2,"x",42,t(20,0,A)),update(3,"color","red",t(20,0,B)),update(4,"x",9,t(15,0,A)),update(5,"x",80,t(20,0,B))));
  var expected=project(ops).snapshot();permutations(ops,0,expected);equal(80,expected.getFirst().fields().get("x").value());equal("red",expected.getFirst().fields().get("color").value());
  var deletes=new ArrayList<>(List.of(create(),remove(),ops.get(1)));permutations(deletes,0,project(deletes).snapshot());equal(0,project(deletes).live().size());
  var duplicates=project(ops);ops.forEach(duplicates::apply);equal(5,duplicates.operations().size());
  var random=new Random(713);
  for(int run=0;run<500;run++){
   var stream=new ArrayList<Operation>();stream.add(create());
   for(int i=0;i<40;i++)stream.add(update(100+i,random.nextBoolean()?"x":"color",i,t(11+random.nextInt(20),i,random.nextBoolean()?A:B)));
   if(run%3==0)stream.add(remove());var state=project(stream).snapshot();
   for(int replica=0;replica<4;replica++){var shuffled=new ArrayList<>(stream);shuffled.addAll(stream.subList(0,5));Collections.shuffle(shuffled,random);equal(state,project(shuffled).snapshot());}
  }
  boolean rejected=false;try{update(2,"points",List.of(),t(20,0,A));}catch(IllegalArgumentException e){rejected=true;}equal(true,rejected);
  System.out.println("PASS: "+assertions+" assertions; 120 delivery permutations; 500 seeded cases x 4 replicas; HLC, remove-wins, duplicate replay.");
 }
}
