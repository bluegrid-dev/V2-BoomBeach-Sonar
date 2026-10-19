package sonar;

import android.system.Os;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Session-only standard view. Original game data stays on this device. */
final class Camera {
    final File root;final String pkg;File target;byte[] before,applied;boolean installed;
    Camera(File root,String pkg){this.root=root;this.pkg=pkg;}
    static String sha(byte[] data)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format("%02x",b&255));return out.toString();}
    static byte[] read(InputStream in)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}
    void apply()throws Exception{
        target=new File("/data/data/"+pkg+"/update/csv/globals.csv");
        byte[] original=null;
        if(target.exists()){before=Files.readAllBytes(target.toPath());original=before;}
        else for(String line:Device.shell("pm path "+pkg).split("\n"))if(line.startsWith("package:"))try(ZipFile zip=new ZipFile(line.substring(8).trim())){
            ZipEntry entry=zip.getEntry("assets/csv/globals.csv");if(entry!=null)try(InputStream in=zip.getInputStream(entry)){original=read(in);}
        }
        if(original==null)throw new IOException("当前游戏安装包未找到原始镜头配置");
        String csv=new String(original,StandardCharsets.UTF_8);
        if(!csv.startsWith("Name,"))throw new IOException("当前游戏镜头文件格式尚不支持；没有写入");
        String changed=replaceNumber(csv,"CAMERA_DISTANCE","130");changed=replaceNumber(changed,"CAMERA_SHAKE","0");
        applied=changed.getBytes(StandardCharsets.UTF_8);
        if(before==null&&target.exists())before=Files.readAllBytes(target.toPath());
        if(before!=null)Files.write(new File(root,"camera-before.bin").toPath(),before);
        JSONObject manifest=new JSONObject().put("target",target.getAbsolutePath()).put("hadFile",before!=null).put("appliedHash",sha(applied));
        Files.write(new File(root,"camera-session.json").toPath(),manifest.toString().getBytes(StandardCharsets.UTF_8));
        File parent=target.getParentFile();boolean created=parent.mkdirs();Files.write(target.toPath(),applied);installed=true;
        int uid=Os.stat("/data/data/"+pkg).st_uid;
        if(created){Os.chown(parent.getAbsolutePath(),uid,uid);Os.chmod(parent.getAbsolutePath(),0700);}Os.chown(target.getAbsolutePath(),uid,uid);Os.chmod(target.getAbsolutePath(),0600);
    }
    static String replaceNumber(String csv,String key,String value)throws Exception{
        java.util.regex.Pattern p=java.util.regex.Pattern.compile("(?m)^"+key+",([^,\\r\\n]+),");java.util.regex.Matcher m=p.matcher(csv);
        if(!m.find())throw new IOException("镜头字段缺失: "+key);String result=csv.substring(0,m.start(1))+value+csv.substring(m.end(1));if(m.find())throw new IOException("镜头字段不唯一: "+key);return result;
    }
    void restore()throws Exception{
        if(!installed)return;
        if(!target.exists()||!Arrays.equals(Files.readAllBytes(target.toPath()),applied))throw new IOException("镜头文件已被外部更改，保留备份等待检查");
        if(before==null)Files.delete(target.toPath());else Files.write(target.toPath(),before);
        Files.deleteIfExists(new File(root,"camera-session.json").toPath());Files.deleteIfExists(new File(root,"camera-before.bin").toPath());installed=false;
    }
}
