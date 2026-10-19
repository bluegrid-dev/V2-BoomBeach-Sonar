package sonar;

import android.graphics.*;
import java.io.*;
import java.util.*;

/** Numeric OCR: licensed rendered fonts plus independently labelled V2
 * screen glyphs, normalized across title and HUD sizes. No legacy references. */
final class Digits {
    static final int W=12,H=20;
    static final class Glyph {int digit;double aspect;boolean[] pixels;Glyph(int d,double a,boolean[]p){digit=d;aspect=a;pixels=p;}}
    final List<Glyph> glyphs=new ArrayList<>();
    final List<Glyph> hudGlyphs=new ArrayList<>();
    Digits(File font)throws IOException{
        // Small HUD numerals have a different shape from the large title font.
        // Render generic, licensed fonts locally; no game glyphs are extracted.
        for(Typeface face:new Typeface[]{Typeface.createFromFile(font),Typeface.create("sans-serif",Typeface.BOLD),Typeface.create("monospace",Typeface.BOLD)}){
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setTypeface(face);p.setColor(Color.WHITE);
        for(int size:new int[]{14,16,18,22,28,40,52})for(float skew:new float[]{0,-0.15f})for(int digit=0;digit<10;digit++){
            Bitmap b=Bitmap.createBitmap(100,90,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(b);canvas.drawColor(Color.BLACK);
            p.setTextSize(size);p.setTextSkewX(skew);canvas.drawText(Integer.toString(digit),20,65,p);
            int[] pixels=new int[9000];b.getPixels(pixels,0,100,0,0,100,90);Vision.Frame f=new Vision.Frame(100,90,pixels);
            List<Vision.Blob> found=Vision.blobs(f,0,0,100,90,Vision.WHITE);
            if(!found.isEmpty()){Vision.Blob best=Collections.max(found,Comparator.comparingInt(c->c.count));glyphs.add(new Glyph(digit,best.w()/(double)best.h(),normal(f,best)));}b.recycle();
        }}
        hudGlyphs.addAll(glyphs);
        InputStream source=Digits.class.getResourceAsStream("/hud.bin");
        if(source==null)throw new IOException("缺少弹药数字参考，请重新构建完整 V2");
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(source))){
            if(in.readInt()!=0x53484431)throw new IOException("弹药数字参考格式不匹配");
            int count=in.readInt();for(int k=0;k<count;k++){int digit=in.readInt();double aspect=in.readFloat();boolean[] pixels=new boolean[W*H];for(int i=0;i<pixels.length;i++)pixels[i]=in.readBoolean();hudGlyphs.add(new Glyph(digit,aspect,pixels));}
        }
    }
    static boolean[] normal(Vision.Frame f,Vision.Blob box){
        boolean[] result=new boolean[W*H];for(int y=0;y<H;y++)for(int x=0;x<W;x++)result[y*W+x]=Vision.WHITE.test(f.p(box.x0+(int)((x+0.5)*box.w()/W),box.y0+(int)((y+0.5)*box.h()/H)));return result;
    }
    int classify(Vision.Frame f,Vision.Blob box,List<Glyph> references){
        boolean[] mask=normal(f,box);double best=1;int value=-1;
        for(Glyph ref:references){int different=0;for(int i=0;i<mask.length;i++)if(mask[i]!=ref.pixels[i])different++;
            double score=different/(double)mask.length+0.15*Math.abs(Math.log((box.w()/(double)box.h())/ref.aspect));
            if(score<best){best=score;value=ref.digit;}}
        return best<0.24?value:-1;
    }
    int read(Vision.Frame f,int x0,int y0,int x1,int y1,boolean prefix){
        List<Vision.Blob> parts=Vision.blobs(f,x0,y0,x1,y1,Vision.WHITE);parts.removeIf(c->c.h()<7||c.w()<2||c.count<10);parts.sort(Comparator.comparingInt(c->c.x0));
        int value=0,count=0;for(Vision.Blob part:parts){int digit=classify(f,part,hudGlyphs);if(digit<0){if(prefix&&count>0)break;return -1;}if(++count>6)return -1;value=value*10+digit;}
        return count==0?-1:value;
    }
    void fill(Vision.Frame f,Vision.Observation o){o.blue=read(f,1087,674,1157,695,false);o.red=read(f,1182,674,1250,695,false);o.level=read(f,520,11,757,58,true);}
}
