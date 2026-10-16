package cn.personal.worktimesync;
import java.util.*;

/** Search only inside the user's granted tree; shared roots prefer the TimeLogger folder. */
final class BackupLocator {
    static final class Entry {
        final String id,name;final boolean directory;final long modified;
        Entry(String id,String name,boolean directory,long modified){this.id=id;this.name=name;this.directory=directory;this.modified=modified;}
    }
    interface Reader {List<Entry> children(String id) throws Exception;}
    private static final class Folder {
        final String id;final int depth;
        Folder(String id,int depth){this.id=id;this.depth=depth;}
    }
    static Entry latest(String root,Reader reader) throws Exception {
        List<Entry> first=reader.children(root);String preferred=null;
        for(Entry e:first)if(e.directory&&"TimeLogger".equalsIgnoreCase(e.name)){preferred=e.id;break;}
        ArrayDeque<Folder> pending=new ArrayDeque<>();Set<String> seen=new HashSet<>();
        pending.add(new Folder(preferred==null?root:preferred,0));Entry latest=null;int count=0;
        while(!pending.isEmpty()){
            Folder folder=pending.remove();if(!seen.add(folder.id))continue;
            List<Entry> entries=folder.id.equals(root)?first:reader.children(folder.id);
            for(Entry e:entries){
                if(++count>1000)throw new Exception("目录内容过多，请授权TimeLogger里的账号目录");
                if(e.directory){
                    if(folder.depth>=4)throw new Exception("目录层级过深，请授权TimeLogger里的账号目录");
                    pending.add(new Folder(e.id,folder.depth+1));
                }else if(e.name!=null&&e.name.endsWith(".bak")&&e.name.contains("自动备份")&&e.modified>0){
                    if(latest==null||e.modified>latest.modified||(e.modified==latest.modified&&e.id.compareTo(latest.id)>0))latest=e;
                }
            }
        }
        if(latest==null)throw new Exception("未找到时间日志自动备份，请在时间日志中开启本地外部自动备份");
        return latest;
    }
}
