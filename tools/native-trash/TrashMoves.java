package com.twig.app;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class TrashMoves {
  private static native int nativeRename(byte[] from, byte[] to);
  static int move(Path from, Path to) { return nativeRename(from.toString().getBytes(StandardCharsets.UTF_8), to.toString().getBytes(StandardCharsets.UTF_8)); }
  public static void main(String[] args) throws Exception {
    System.load(args[0]);
    Path dir=Files.createTempDirectory("twig-trash-native-");
    try {
      Path source=Files.writeString(dir.resolve("原始😀.txt"),"original");
      Path target=Files.writeString(dir.resolve("target.txt"),"existing");
      if(move(source,target)==0 || !Files.readString(target).equals("existing") || !Files.readString(source).equals("original")) throw new AssertionError("overwrote existing file");
      Files.delete(target);
      if(move(source,target)!=0 || Files.exists(source) || !Files.readString(target).equals("original")) throw new AssertionError("UTF-8 move failed");
      Path a=Files.writeString(dir.resolve("a"),"A"), b=Files.writeString(dir.resolve("b"),"B"), dest=dir.resolve("race");
      CountDownLatch start=new CountDownLatch(1);
      ExecutorService pool=Executors.newFixedThreadPool(2);
      try {
        Future<Integer> x=pool.submit(()->{start.await();return move(a,dest);});
        Future<Integer> y=pool.submit(()->{start.await();return move(b,dest);});
        start.countDown();
        int first=x.get(), second=y.get();
        if((first==0)==(second==0) || Files.exists(a)==Files.exists(b)) throw new AssertionError("race did not preserve losing source");
        System.out.println("PASS: atomic collision refusal, UTF-8 supplementary filenames, concurrent destination creation (one winner; losing source retained)");
      } finally { pool.shutdownNow(); }
      Path folder=Files.createDirectory(dir.resolve("folder")), restored=dir.resolve("restored");
      Files.writeString(folder.resolve("inside"),"keep");
      if(move(folder,restored)!=0 || !Files.readString(restored.resolve("inside")).equals("keep")) throw new AssertionError("directory move failed");
      Files.delete(restored.resolve("inside"));Files.delete(restored);
    } finally {
      try(var files=Files.list(dir)){ for(Path p:files.toList()) Files.delete(p); }
      Files.delete(dir);
    }
  }
}
