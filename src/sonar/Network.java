package sonar;

import android.system.*;
import java.io.*;
import java.net.SocketAddress;
import java.nio.*;
import java.util.*;

/** Target-UID packet isolation and Linux SOCK_DIAG connection disposal.
 * All bytes below describe public Linux UAPI, not a game protocol. */
final class Network {
    final int uid;final String chain="SONAR_INDEPENDENT";boolean ipv4,ipv6,offline;
    Network(String pkg)throws Exception{uid=Os.stat("/data/data/"+pkg).st_uid;}
    void isolate()throws Exception{
        for(String binary:new String[]{"iptables","ip6tables"}){
            Device.shell(binary+" -N "+chain);
            if(binary.equals("iptables"))ipv4=true;else ipv6=true;
            Device.shell(binary+" -A "+chain+" -j DROP");
            Device.shell(binary+" -I OUTPUT 1 -m owner --uid-owner "+uid+" -j "+chain);
        }offline=true;
    }
    void restore()throws Exception{
        Exception failure=null;
        for(String binary:new String[]{"iptables","ip6tables"})if(binary.equals("iptables")?ipv4:ipv6){
            try{
                // Remove only this runner's jump and chain; never flush shared tables.
                String check=Device.shell(binary+" -S OUTPUT");
                if(check.contains("-j "+chain))Device.shell(binary+" -D OUTPUT -m owner --uid-owner "+uid+" -j "+chain);
                Device.shell(binary+" -F "+chain);Device.shell(binary+" -X "+chain);
                if(binary.equals("iptables"))ipv4=false;else ipv6=false;
            }catch(Exception e){failure=e;}
        }offline=ipv4||ipv6;if(failure!=null)throw failure;
    }
    static FileDescriptor diagnostic()throws Exception{
        FileDescriptor fd=Os.socket(16,2,4);
        try{
            SocketAddress address=(SocketAddress)Class.forName("android.system.NetlinkSocketAddress").getConstructor(int.class,int.class).newInstance(0,0);
            Os.connect(fd,address);Os.setsockoptTimeval(fd,1,20,StructTimeval.fromMillis(1500));return fd;
        }catch(Exception e){Os.close(fd);throw e;}
    }
    static byte[] request(int family,int type,int flags,byte[] id){
        ByteBuffer b=ByteBuffer.allocate(72).order(ByteOrder.nativeOrder());b.putInt(72).putShort((short)type).putShort((short)flags).putInt(1).putInt(0);
        b.put((byte)family).put((byte)6).put((byte)0).put((byte)0).putInt(-1);if(id!=null)b.put(id);return b.array();
    }
    List<byte[]> sockets(int family)throws Exception{
        FileDescriptor fd=diagnostic();List<byte[]> result=new ArrayList<>();
        try{
            byte[] req=request(family,20,0x301,null);Os.write(fd,req,0,req.length);byte[] data=new byte[32768];
            while(true){int count=Os.read(fd,data,0,data.length);if(count<=0)throw new IOException("内核连接查询中断");ByteBuffer b=ByteBuffer.wrap(data,0,count).order(ByteOrder.nativeOrder());
                for(int start=0;start+16<=count;){int len=b.getInt(start),type=b.getShort(start+4)&65535;if(len<16||start+len>count)throw new IOException("内核连接消息格式错误");
                    if(type==3)return result;
                    if(type==2){int error=b.getInt(start+16);if(error!=0)throw new IOException("内核连接查询拒绝: "+error);}
                    if(type==20&&len>=88&&b.getInt(start+16+64)==uid)result.add(Arrays.copyOfRange(data,start+20,start+68));
                    start+=(len+3)&~3;
                }
            }
        }finally{Os.close(fd);}
    }
    int disposeConnections()throws Exception{
        int disposed=0;
        for(int family:new int[]{2,10})for(byte[] id:sockets(family)){
            FileDescriptor fd=diagnostic();try{byte[] req=request(family,21,5,id);Os.write(fd,req,0,req.length);byte[] data=new byte[4096];int n=Os.read(fd,data,0,data.length);ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.nativeOrder());
                if(n<20||b.getShort(4)!=2)throw new IOException("内核没有确认连接关闭");int error=b.getInt(16);if(error!=0&&error!=-2)throw new IOException("内核连接关闭失败: "+error);disposed++;
            }finally{Os.close(fd);}
        }return disposed;
    }
}
