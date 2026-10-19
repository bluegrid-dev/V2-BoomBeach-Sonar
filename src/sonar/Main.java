package sonar;

import android.os.Looper;
import java.io.*;
import java.nio.channels.*;
import org.json.JSONObject;

public final class Main {
    public static void main(String[] args) throws Exception {
        try { execute(args); } catch(Throwable error) {error.printStackTrace(System.out);System.exit(1);}
    }
    static void execute(String[] args) throws Throwable {
        File root=new File("/data/local/tmp/sonar-independent");root.mkdirs();
        if(args.length>0&&args[0].equals("run")){
            // Own the instance before parsing or initializing Android resources.
            // A rejected second process must never overwrite the active status.
            try(RandomAccessFile lockFile=new RandomAccessFile(new File(root,"run.lock"),"rw");FileChannel channel=lockFile.getChannel();FileLock lock=channel.tryLock()){
                if(lock==null)throw new IOException("设备侧已有运行实例");
                Session session;
                try{
                    Looper.prepareMainLooper();
                    JSONObject config=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(root,"run.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
                    session=new Session(root,config);
                }catch(Throwable error){
                    Session.atomic(new File(root,"status.json"),new JSONObject().put("state","error").put("phase","启动失败："+(error.getMessage()==null?error.toString():error.getMessage())).put("elapsedMs",0).put("time",System.currentTimeMillis()).toString());
                    throw error;
                }
                session.execute();
            }
        }
    }
}
