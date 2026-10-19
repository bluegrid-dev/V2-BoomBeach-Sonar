package sonar;

import java.util.*;

/** Visible geometry and a small independently trained cell classifier.
 * No game API, hidden layout, extracted asset, or legacy template is used. */
final class Vision {
    static CellModel model;
    static final int UNKNOWN=0, WATER=1, SHIP=2;
    static final class Frame {
        final int w,h;final int[] pixels;
        Frame(int w,int h,int[] pixels){this.w=w;this.h=h;this.pixels=pixels;}
        int p(int x,int y){return pixels[Math.max(0,Math.min(h-1,y))*w+Math.max(0,Math.min(w-1,x))];}
    }
    interface Mask {boolean test(int color);}
    static int r(int p){return (p>>16)&255;}static int g(int p){return(p>>8)&255;}static int b(int p){return p&255;}
    static final Mask LINE=p->r(p)>145 && g(p)-r(p)>0 && g(p)-r(p)<45 && b(p)-g(p)>=0 && b(p)-g(p)<30;
    static final Mask WHITE=p->r(p)>205 && g(p)>205 && b(p)>180 && Math.abs(r(p)-g(p))<25;
    static final class Blob {
        int x0,y0,x1,y1,count;double row,col;boolean assigned;
        Blob(int x,int y){x0=x1=x;y0=y1=y;}
        int w(){return x1-x0+1;}int h(){return y1-y0+1;}
        double x(){return(x0+x1)/2.0;}double y(){return(y0+y1)/2.0;}
    }
    static List<Blob> blobs(Frame f,int x0,int y0,int x1,int y1,Mask rule){
        int w=x1-x0,h=y1-y0;byte[] mask=new byte[w*h];int[] queue=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(rule.test(f.p(x+x0,y+y0)))mask[y*w+x]=1;
        List<Blob> result=new ArrayList<>();
        for(int pos=0;pos<mask.length;pos++)if(mask[pos]==1){
            int head=0,tail=0;queue[tail++]=pos;mask[pos]=0;Blob c=new Blob(pos%w+x0,pos/w+y0);
            while(head<tail){int at=queue[head++],x=at%w,y=at/w;c.count++;c.x0=Math.min(c.x0,x+x0);c.x1=Math.max(c.x1,x+x0);c.y0=Math.min(c.y0,y+y0);c.y1=Math.max(c.y1,y+y0);
                if(x>0 && mask[at-1]==1){mask[at-1]=0;queue[tail++]=at-1;}
                if(x+1<w && mask[at+1]==1){mask[at+1]=0;queue[tail++]=at+1;}
                if(y>0 && mask[at-w]==1){mask[at-w]=0;queue[tail++]=at-w;}
                if(y+1<h && mask[at+w]==1){mask[at+w]=0;queue[tail++]=at+w;}
            }result.add(c);
        }return result;
    }
    static final class Grid {
        final int n;final double[] h;
        double[][] buoyRc;
        Grid(int n,double[] h){this.n=n;this.h=h;}
        double[] xy(double r,double c){double z=h[6]*r+h[7]*c+1;return new double[]{(h[0]*r+h[1]*c+h[2])/z,(h[3]*r+h[4]*c+h[5])/z};}
        double[] rc(double x,double y){double a=h[0]-x*h[6],b=h[1]-x*h[7],c=x-h[2],d=h[3]-y*h[6],e=h[4]-y*h[7],f=y-h[5],det=a*e-b*d;return new double[]{(c*e-b*f)/det,(a*f-c*d)/det};}
        Grid shift(int r,int c){double z=h[6]*r+h[7]*c+1;return new Grid(n,new double[]{h[0]/z,h[1]/z,(h[0]*r+h[1]*c+h[2])/z,h[3]/z,h[4]/z,(h[3]*r+h[4]*c+h[5])/z,h[6]/z,h[7]/z});}
    }
    static List<Blob> tiles(Frame f){
        List<Blob> tiles=new ArrayList<>();
        for(Blob c:blobs(f,280,100,1040,610,LINE))if(c.w()>=38&&c.w()<=85&&c.h()>=24&&c.h()<=65&&c.w()/(double)c.h()>1.15&&c.w()/(double)c.h()<1.8&&c.count>100&&c.count<0.24*c.w()*c.h())tiles.add(c);
        return tiles;
    }
    static Grid lattice(List<Blob> tiles){
        List<Blob> largest=new ArrayList<>();
        for(Blob seed:tiles)if(!seed.assigned){
            seed.row=seed.col=0;seed.assigned=true;List<Blob> group=new ArrayList<>();group.add(seed);
            for(int i=0;i<group.size();i++)for(Blob candidate:tiles)if(!candidate.assigned){
                Blob cell=group.get(i);double dx=candidate.x()-cell.x(),dy=candidate.y()-cell.y();
                double ex=(cell.w()+candidate.w())/4.0+3,ey=(cell.h()+candidate.h())/4.0+2;
                // Open cells split the covers into separate groups. Their
                // centres still differ by integer steps on the same lattice.
                int dr=(int)Math.round((dy/ey-dx/ex)/2),dc=(int)Math.round((dy/ey+dx/ex)/2);
                if((dr!=0||dc!=0)&&Math.abs(dx-(dc-dr)*ex)<3&&Math.abs(dy-(dc+dr)*ey)<3){
                    candidate.row=cell.row+dr;candidate.col=cell.col+dc;
                    candidate.assigned=true;group.add(candidate);
                }
            }if(group.size()>largest.size())largest=group;
        }
        if(largest.size()<4)return null;
        double[] h=fit(largest);if(h==null)return null;Grid initial=new Grid(0,h);
        List<Blob> inliers=new ArrayList<>();int minr=100,minc=100,maxr=-100;
        for(Blob cell:tiles){double[] rc=initial.rc(cell.x(),cell.y());int r=(int)Math.round(rc[0]),c=(int)Math.round(rc[1]);double[] p=initial.xy(r,c);
            if(Math.hypot(p[0]-cell.x(),p[1]-cell.y())<2.5){cell.row=r;cell.col=c;inliers.add(cell);minr=Math.min(minr,r);minc=Math.min(minc,c);maxr=Math.max(maxr,r);}}
        int nr=maxr-minr+1;
        h=fit(inliers);if(h==null)return null;return new Grid(nr,h).shift(minr,minc);
    }
    static Grid locateBoard(Frame f,List<Blob> tiles,Grid known,boolean[] opened){
        Grid relative=lattice(tiles);if(relative==null)return null;
        Set<String> corners=new HashSet<>();
        for(Blob blob:blobs(f,260,60,1080,635,p->r(p)>80&&Math.max(r(p),Math.max(g(p),b(p)))-Math.min(r(p),Math.min(g(p),b(p)))<35)){
            if(blob.w()<6||blob.w()>35||blob.h()<5||blob.h()>30||blob.count<15)continue;
            double[] rc=relative.rc(blob.x(),blob.y());int r=(int)Math.round(rc[0]-.5),c=(int)Math.round(rc[1]-.5);
            corners.add(r+","+c);
        }
        List<int[]> covers=new ArrayList<>();int minr=100,minc=100,maxr=-100,maxc=-100;
        for(Blob tile:tiles){double[] rc=relative.rc(tile.x(),tile.y());int r=(int)Math.round(rc[0]),c=(int)Math.round(rc[1]);double[] xy=relative.xy(r,c);
            if(Math.hypot(xy[0]-tile.x(),xy[1]-tile.y())<2.5){covers.add(new int[]{r,c});minr=Math.min(minr,r);minc=Math.min(minc,c);maxr=Math.max(maxr,r);maxc=Math.max(maxc,c);}}
        Grid best=null;int bestCount=-1;boolean ambiguous=false;
        // Current covers determine the plane and constrain every possible board
        // boundary. Visible buoys supply the missing boundaries, not a fixed
        // three-icon gate. A known board spanning all rows/columns needs none.
        for(int n=known==null?3:known.n;n<=(known==null?10:known.n);n++){
            for(int r=maxr-n+1;r<=minr;r++)for(int c=maxc-n+1;c<=minc;c++){
                int count=0;for(int k=0;k<4;k++)if(corners.contains((r-2+(k/2)*(n+2))+","+(c-2+(k%2)*(n+2))))count++;
                boolean fullSpan=known!=null&&maxr-minr+1==n&&maxc-minc+1==n;
                if(count<2&&!fullSpan)continue;
                boolean matches=true;
                if(opened!=null)for(int[] cover:covers)if(opened[(cover[0]-r)*n+cover[1]-c]){matches=false;break;}
                if(!matches)continue;
                if(count>bestCount){best=new Grid(n,relative.h).shift(r,c);bestCount=count;ambiguous=false;}
                else if(count==bestCount)ambiguous=true;
            }
        }return ambiguous?null:best;
    }
    static Blob[] boardBuoys(Frame f,Grid grid){
        Blob[] found=new Blob[4];
        for(Blob b:blobs(f,260,60,1080,635,p->r(p)>80&&Math.max(r(p),Math.max(g(p),b(p)))-Math.min(r(p),Math.min(g(p),b(p)))<35)){
            if(b.w()<6||b.w()>35||b.h()<5||b.h()>30||b.count<15)continue;
            double[] rc=grid.rc(b.x(),b.y());int r=(int)Math.round(rc[0]-.5),c=(int)Math.round(rc[1]-.5);
            if((r!=-2&&r!=grid.n)||(c!=-2&&c!=grid.n))continue;
            int corner=(r==grid.n?2:0)+(c==grid.n?1:0);
            if(found[corner]==null||b.count>found[corner].count)found[corner]=b;
        }return found;
    }
    static void rememberBuoys(Frame f,Grid grid){
        grid.buoyRc=new double[4][];Blob[] buoys=boardBuoys(f,grid);
        // Calibrate the visible buoy mesh against this frame's tile plane.
        // Its image centre is above the water, not the geometric corner itself.
        for(int k=0;k<4;k++)if(buoys[k]!=null)grid.buoyRc[k]=grid.rc(buoys[k].x(),buoys[k].y());
    }
    static Grid sparseBoard(Frame f,List<Blob> tiles,Grid known){
        if(known==null||known.buoyRc==null)return null;
        Blob[] buoys=boardBuoys(f,known);List<Blob> points=new ArrayList<>();
        for(int k=0;k<4;k++){
            if(buoys[k]==null||known.buoyRc[k]==null)return null;
            buoys[k].row=known.buoyRc[k][0];buoys[k].col=known.buoyRc[k][1];points.add(buoys[k]);
        }
        double[] h=fit(points);if(h==null)return null;Grid grid=new Grid(known.n,h);
        // Disconnected rows still constrain the same plane. Assign them with
        // the buoy projection, then fit all current image features together.
        for(Blob tile:tiles){double[] rc=grid.rc(tile.x(),tile.y());tile.row=Math.round(rc[0]);tile.col=Math.round(rc[1]);points.add(tile);}
        h=fit(points);if(h==null)return null;grid=new Grid(known.n,h);
        // Open cells still need coordinates when too few covers remain to fit
        // a plane. Four currently visible buoys supply that plane; remaining
        // covers must agree with it, using the same pixel tolerance as read().
        for(Blob tile:tiles){double[] rc=grid.rc(tile.x(),tile.y());int r=(int)Math.round(rc[0]),c=(int)Math.round(rc[1]);double[] xy=grid.xy(r,c);
            if(r<0||r>=grid.n||c<0||c>=grid.n||Math.hypot(xy[0]-tile.x(),xy[1]-tile.y())>=4)return null;
        }
        grid.buoyRc=known.buoyRc;return grid;
    }
    static double[] fit(List<Blob> points){
        // A projective plane needs four points with no three on one line.
        // If all but one point share a line, the equations also admit a
        // collapsed mapping whose numerator and denominator vanish there.
        // Pixel noise can make that invalid solution pass the pivot check.
        List<Blob> anchors=new ArrayList<>();
        for(Blob p:points){boolean duplicate=false;for(Blob a:anchors)if(p.row==a.row&&p.col==a.col)duplicate=true;
            if(!duplicate)anchors.add(p);if(anchors.size()==3)break;}
        if(points.size()<4||anchors.size()<3)return null;
        // Any line containing all but one point must contain two anchors.
        for(int a=0;a<2;a++)for(int b=a+1;b<3;b++){
            Blob origin=anchors.get(a),end=anchors.get(b);double dr=end.row-origin.row,dc=end.col-origin.col;int outside=0;
            for(Blob p:points)if((p.row-origin.row)*dc!=(p.col-origin.col)*dr)outside++;
            if(outside<2)return null;
        }
        double[][] normal=new double[8][9];
        for(Blob p:points){double r=p.row,c=p.col,x=p.x(),y=p.y();accumulate(normal,new double[]{r,c,1,0,0,0,-x*r,-x*c},x);accumulate(normal,new double[]{0,0,0,r,c,1,-y*r,-y*c},y);}
        for(int col=0;col<8;col++){
            int best=col;for(int row=col+1;row<8;row++)if(Math.abs(normal[row][col])>Math.abs(normal[best][col]))best=row;
            if(Math.abs(normal[best][col])<1e-8)return null;double[] tmp=normal[col];normal[col]=normal[best];normal[best]=tmp;
            double v=normal[col][col];for(int j=col;j<9;j++)normal[col][j]/=v;
            for(int row=0;row<8;row++)if(row!=col){v=normal[row][col];for(int j=col;j<9;j++)normal[row][j]-=v*normal[col][j];}
        }double[] h=new double[8];for(int i=0;i<8;i++)h[i]=normal[i][8];return h;
    }
    static void accumulate(double[][] a,double[] row,double value){for(int i=0;i<8;i++){for(int j=0;j<8;j++)a[i][j]+=row[i]*row[j];a[i][8]+=row[i]*value;}}
    static final class Observation {
        Grid grid;int[] cells,fleet;boolean[] covered;final int[] completed=new int[6];final List<int[]> sunk=new ArrayList<>();int blue=-1,red=-1,level=-1;String page="unknown",outline="";int targetX,targetY;
    }
    static int attackResult(Observation before,Observation after,int cell){
        if(!after.page.equals("board")||after.covered[cell])return UNKNOWN;
        // The final hit raises the whole hull away from the cell centre. The
        // newly dimmed sidebar icon is the visible result of that same attack.
        for(int len=2;len<=5;len++)if(after.completed[len]>before.completed[len])return SHIP;
        return after.cells[cell];
    }
    static int[] fleet(Frame f,int[] completed){
        List<Blob> silhouettes=blobs(f,24,250,132,570,p->r(p)>35&&g(p)>65&&g(p)-r(p)<50);
        List<Integer> lengths=new ArrayList<>();
        silhouettes.sort(Comparator.comparingInt(p->p.y0));
        for(Blob s:silhouettes)if(s.w()>45&&s.h()>11){
            List<Blob> holes=blobs(f,s.x0,s.y0,s.x1+1,s.y1+1,p->r(p)<40&&g(p)<70);int count=0;double minY=Double.MAX_VALUE,maxY=0;
            for(Blob hole:holes)if(hole.x0>s.x0&&hole.x1<s.x1&&hole.y0>s.y0&&hole.y1<s.y1&&hole.w()>=4&&hole.w()<=11&&hole.h()>=4&&hole.h()<=11){count++;minY=Math.min(minY,hole.y());maxY=Math.max(maxY,hole.y());}
            // Conning tower and hull may form one component. Identify the aligned
            // portholes rather than rejecting a correct icon because it is taller.
            if(count>=2&&count<=5&&maxY-minY<=2){
                lengths.add(count);boolean bright=false;
                for(int y=s.y0;y<=s.y1&&!bright;y++)for(int x=s.x0;x<=s.x1;x++)if(WHITE.test(f.p(x,y))){bright=true;break;}
                if(!bright)completed[count]++;
            }
        }int[] result=new int[lengths.size()];for(int i=0;i<result.length;i++)result[i]=lengths.get(i);return result;
    }
    static void removeRaisedHullSpill(Observation o){
        // A completed hull is raised above its cells. Its mesh can cover a
        // neighbouring tile centre; that pixel is not a new ship observation.
        // Locate its straight body using the dim sidebar icon and opened rim.
        int n=o.grid.n;List<int[]> bodies=new ArrayList<>();
        for(int len=2;len<=5;len++)if(o.completed[len]>0){
            List<int[]> candidates=new ArrayList<>();
            for(int d=0;d<2;d++)for(int r=0;r<n;r++)for(int c=0;c<n;c++){
                int er=r+(d==0?len-1:0),ec=c+(d==1?len-1:0);if(er>=n||ec>=n)continue;
                boolean visible=true;
                for(int y=Math.max(0,r-1);y<=Math.min(n-1,er+1);y++)for(int x=Math.max(0,c-1);x<=Math.min(n-1,ec+1);x++)if(o.cells[y*n+x]==UNKNOWN)visible=false;
                for(int k=0;k<len;k++)if(o.cells[(r+(d==0?k:0))*n+c+(d==1?k:0)]!=SHIP)visible=false;
                if(d==0){if(r>0&&o.cells[(r-1)*n+c]==SHIP)visible=false;if(er+1<n&&o.cells[(er+1)*n+c]==SHIP)visible=false;}
                else {if(c>0&&o.cells[r*n+c-1]==SHIP)visible=false;if(ec+1<n&&o.cells[r*n+ec+1]==SHIP)visible=false;}
                if(visible)candidates.add(new int[]{r,c,er,ec});
            }
            if(candidates.size()==o.completed[len])bodies.addAll(candidates);
        }
        boolean[] body=new boolean[n*n];for(int[] a:bodies){int[] cells=new int[a[2]-a[0]+a[3]-a[1]+1];int at=0;for(int r=a[0];r<=a[2];r++)for(int c=a[1];c<=a[3];c++){body[r*n+c]=true;cells[at++]=r*n+c;}o.sunk.add(cells);}
        for(int[] a:bodies)for(int r=Math.max(0,a[0]-1);r<=Math.min(n-1,a[2]+1);r++)for(int c=Math.max(0,a[1]-1);c<=Math.min(n-1,a[3]+1);c++){
            int cell=r*n+c;if(!body[cell]&&o.cells[cell]==SHIP)o.cells[cell]=UNKNOWN;
        }
    }
    static Blob homeEntryMain(Frame f){
        return homeEntry(f,300);
    }
    static Blob homeEntry(Frame f,int top){
        // Join the dotted border of the visible participation label. This is
        // the world-object entrance, whose position changes with the home camera.
        int x0=300,y0=top,w=750,h=720-top;int[] joined=new int[w*h];
        for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){int p=f.p(x+x0,y+y0);
            if(r(p)<70&&g(p)<70&&b(p)<70)for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)joined[(y+dy)*w+x+dx]=-1;
        }
        Blob found=null;
        for(Blob box:blobs(new Frame(w,h,joined),0,0,w,h,p->p==-1)){
            if(box.w()<24||box.w()>80||box.h()<10||box.h()>36||box.w()<2*box.h()||box.w()>4*box.h())continue;
            // The participation bubble stays transparent inside its dotted
            // border. Building countdown bars have a dark filled interior.
            int white=0,dark=0,total=0;
            for(int y=box.y0+2;y<box.y1-2;y++)for(int x=box.x0+3;x<box.x1-3;x++){
                int p=f.p(x+x0,y+y0);total++;if(WHITE.test(p))white++;
                if(r(p)<70&&g(p)<70&&b(p)<70)dark++;
            }
            if(white<6||dark>=total*0.12)continue;if(found!=null)return null;
            box.x0+=x0;box.x1+=x0;box.y0+=y0;box.y1+=y0;found=box;
        }return found;
    }
    static Observation read(Frame f,Grid known,boolean[] opened){
        Observation o=new Observation();List<Blob> tiles=tiles(f);o.fleet=fleet(f,o.completed);
        StringBuilder outline=new StringBuilder();for(Blob tile:tiles)outline.append(tile.x0).append(',').append(tile.y0).append(',').append(tile.x1).append(',').append(tile.y1).append(';');o.outline=outline.toString();
        // Page signatures are measured in the supplied 1280x720 player images.
        if(fraction(f,470,175,810,320,p->r(p)>170&&g(p)>100&&b(p)<90)>0.40 && b(f.p(520,360))>g(f.p(520,360))){o.page="victory";o.targetX=640;o.targetY=540;return o;}
        if(fraction(f,270,215,1000,230,p->Math.abs(r(p)-g(p))<3&&Math.abs(g(p)-b(p))<3&&r(p)>45&&r(p)<95)>0.95 && fraction(f,300,440,400,485,p->g(p)>140&&b(p)>140&&r(p)<175)>0.03){o.page="connection";o.targetX=345;o.targetY=461;return o;}
        if(fraction(f,550,567,727,621,p->g(p)>130&&r(p)>65&&b(p)<110&&g(p)>r(p))>0.4){o.page="login";o.targetX=640;o.targetY=593;return o;}
        if(fraction(f,910,228,1165,289,WHITE)>0.5&&fraction(f,1180,228,1220,286,p->g(p)>r(p)*1.25&&g(p)>b(p)*1.1)>0.2){o.page="events";o.targetX=1199;o.targetY=256;return o;}
        if(o.fleet.length>0){
            // Covers alone can match a different row after a camera pan. Use
            // the visible outer buoys to anchor every read, including re-entry.
            o.grid=locateBoard(f,tiles,known,opened);
            if(o.grid!=null)rememberBuoys(f,o.grid);
            else o.grid=sparseBoard(f,tiles,known);
            if(known!=null&&o.grid!=null&&o.grid.n!=known.n)o.grid=null;
            // An actual opened cell cannot acquire a cover when the camera
            // moves. Apply this check to both geometry fits before using them.
            if(opened!=null&&o.grid!=null)for(Blob tile:tiles){double[] rc=o.grid.rc(tile.x(),tile.y());int r=(int)Math.round(rc[0]),c=(int)Math.round(rc[1]);double[] xy=o.grid.xy(r,c);
                if(r>=0&&r<o.grid.n&&c>=0&&c<o.grid.n&&opened[r*o.grid.n+c]&&Math.hypot(xy[0]-tile.x(),xy[1]-tile.y())<4){o.grid=null;break;}}
            if(o.grid!=null){o.page="board";int n=o.grid.n;o.cells=new int[n*n];o.covered=new boolean[n*n];
                for(int r=0;r<n;r++)for(int c=0;c<n;c++){
                    double[] xy=o.grid.xy(r,c);boolean covered=false;
                    for(Blob tile:tiles)if(Math.hypot(xy[0]-tile.x(),xy[1]-tile.y())<4){covered=true;break;}
                    if(covered){o.covered[r*n+c]=true;o.cells[r*n+c]=UNKNOWN;continue;}
                    int seen=model.classify(f,o.grid,r,c);
                    o.covered[r*n+c]=seen==0;
                    o.cells[r*n+c]=seen==3?UNKNOWN:seen;
                }
                removeRaisedHullSpill(o);
            }
        }
        // The portrait varies with the account. The home map compass and land
        // remain visible after a native home reload.
        if(o.page.equals("unknown")&&fraction(f,1185,622,1255,695,p->r(p)>180&&g(p)>135&&b(p)<140)>0.15&&fraction(f,550,200,800,390,p->g(p)>r(p)*1.2&&g(p)>b(p)*1.5)>0.4){o.page="home";o.targetX=431;o.targetY=709;}
        return o;
    }
    static double fraction(Frame f,int x0,int y0,int x1,int y1,Mask rule){int count=0,total=0;for(int y=y0;y<y1;y+=2)for(int x=x0;x<x1;x+=2){total++;if(rule.test(f.p(x,y)))count++;}return count/(double)total;}
}
