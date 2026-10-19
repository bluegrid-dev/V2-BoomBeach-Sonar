package sonar;

import java.io.*;

/** Small independently trained CNN. Inputs are RGB screen crops only.
 * 64x64 RGB -> conv8/2 -> conv8/2 -> dense32 -> covered/water/ship/obscured.
 * Kept in Java so the same weights run on Android and screenshot regression. */
final class CellModel {
    final float[] w1=new float[216],b1=new float[8],w2=new float[576],b2=new float[8];
    final float[] w3=new float[65536],b3=new float[32],w4=new float[128],b4=new float[4];
    final float[] input=new float[12288],a=new float[8192],b=new float[2048],c=new float[32],out=new float[4];
    long nanos,calls;
    CellModel(InputStream source)throws IOException{
        if(source==null)throw new IOException("缺少棋盘识别模型，请重新构建完整 V2");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(source))){
            if(in.readInt()!=0x53434E32)throw new IOException("棋盘模型格式不匹配");
            for(float[] values:new float[][]{w1,b1,w2,b2,w3,b3,w4,b4})for(int i=0;i<values.length;i++)values[i]=in.readFloat();
        }
    }
    int classify(Vision.Frame f,Vision.Grid grid,int row,int col){
        long start=System.nanoTime();double[] h=grid.h;
        for(int y=0;y<64;y++)for(int x=0;x<64;x++){
            double r=row+(y+.5)/64*2.5-1.25,c=col+(x+.5)/64*2.5-1.25,z=h[6]*r+h[7]*c+1;
            int p=f.p((int)Math.round((h[0]*r+h[1]*c+h[2])/z),(int)Math.round((h[3]*r+h[4]*c+h[5])/z)),i=y*64+x;
            input[i]=Vision.r(p)/255f;input[4096+i]=Vision.g(p)/255f;input[8192+i]=Vision.b(p)/255f;
        }
        conv(input,64,3,a,8,w1,b1);conv(a,32,8,b,8,w2,b2);dense(b,c,w3,b3,true);dense(c,out,w4,b4,false);
        int best=0;for(int k=1;k<4;k++)if(out[k]>out[best])best=k;
        nanos+=System.nanoTime()-start;calls++;return best;
    }
    static void conv(float[] input,int size,int channels,float[] output,int count,float[] weights,float[] bias){
        int side=size/2;
        for(int o=0;o<count;o++)for(int y=0;y<side;y++)for(int x=0;x<side;x++){
            float sum=bias[o];
            for(int c=0;c<channels;c++)for(int ky=0;ky<3;ky++){
                int iy=y*2+ky-1;if(iy<0||iy>=size)continue;
                for(int kx=0;kx<3;kx++){int ix=x*2+kx-1;if(ix>=0&&ix<size)sum+=input[(c*size+iy)*size+ix]*weights[((o*channels+c)*3+ky)*3+kx];}
            }output[(o*side+y)*side+x]=Math.max(0,sum);
        }
    }
    static void dense(float[] input,float[] output,float[] weights,float[] bias,boolean relu){
        for(int o=0;o<output.length;o++){float sum=bias[o];int base=o*input.length;for(int i=0;i<input.length;i++)sum+=input[i]*weights[base+i];output[o]=relu?Math.max(0,sum):sum;}
    }
}
