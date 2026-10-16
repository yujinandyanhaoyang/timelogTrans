package cn.personal.worktimesync;
import java.util.*;
public class BackupLocatorTest {
    static BackupLocator.Entry file(String id,long time){return new BackupLocator.Entry(id,"手机(自动备份).bak",false,time);}
    static BackupLocator.Entry dir(String id,String name){return new BackupLocator.Entry(id,name,true,0);}
    static void require(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args) throws Exception {
        Map<String,List<BackupLocator.Entry>> tree=new HashMap<>();
        tree.put("downloads",Arrays.asList(file("old-test-copy",999),dir("timelogger","TimeLogger"),dir("unrelated","其他应用")));
        tree.put("timelogger",Arrays.asList(dir("account","账号")));
        tree.put("account",Arrays.asList(file("current",100),file("older",90),dir("archive","循环备份")));
        tree.put("archive",Arrays.asList(file("archived",80)));
        BackupLocator.Reader reader=id->{if(!tree.containsKey(id))throw new AssertionError("unrelated directory visited");return tree.get(id);};
        require(BackupLocator.latest("downloads",reader).id.equals("current"));
        require(BackupLocator.latest("timelogger",reader).id.equals("current"));
        require(BackupLocator.latest("account",reader).id.equals("current"));
        tree.put("account",Arrays.asList(file("new-record-backup",101),file("current",100)));
        require(BackupLocator.latest("downloads",reader).id.equals("new-record-backup"));
        tree.put("account",Collections.emptyList());
        try{BackupLocator.latest("downloads",reader);throw new AssertionError();}catch(Exception expected){}
        tree.put("timelogger",Arrays.asList(dir("timelogger","TimeLogger")));
        try{BackupLocator.latest("downloads",reader);throw new AssertionError();}catch(Exception expected){}
        System.out.println("PASS: nested account backup, TimeLogger preferred over test copy, newest refresh, direct grants, empty/cyclic tree safe failure");
    }
}
