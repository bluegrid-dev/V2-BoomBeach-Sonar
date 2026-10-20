package sonar;

import android.graphics.Bitmap;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;

/** The entire observe/decide/act loop lives on Android. Windows only controls it. */
final class Session {
    final File root,pending,stop;final JSONObject config;final String pkg;
    final Device device;final Digits digits;final Network network;final Camera camera;
    Board board;Bitmap latest;Vision.Frame frame;int blueActions,redActions,levels,frames;long started=SystemClock.elapsedRealtime();boolean stopping;
    String phase="启动",outcome="running";JSONObject unresolved;FileOutputStream trace;
    boolean stopPending,actionInFlight,reviewUsed,actionReviewUsed,resourceReviewUsed;
    ScoutProgress scoutProgress;boolean nextLevelPending;
    Session(File root,JSONObject config)throws Exception{
        this.root=root;this.config=config;pkg=config.getString("package");
        if(!pkg.equals("com.supercell.boombeach"))throw new IOException("只支持海岛奇兵国际服包名");
        pending=new File(root,"pending.json");stop=new File(root,"stop");
        Vision.model=new CellModel(Session.class.getResourceAsStream("/cells.bin"));
        device=new Device(root);digits=new Digits(new File(root,"Montserrat-Bold.ttf"));network=new Network(pkg);camera=new Camera(root,pkg);
    }
    void execute()throws Exception{
        // Main holds run.lock for initialization and the complete session.
        try{
            if(new File(root,"camera-session.json").exists())reject("上一轮镜头会话未收尾，请先检查，不覆盖原备份");
            // An explicit Start begins a new observation session. Retain an old
            // unresolved action as history, never replay it or lock the new board.
            if(pending.exists())Files.move(pending.toPath(),new File(root,"pending-history-"+System.currentTimeMillis()+".json").toPath());
            Files.deleteIfExists(stop.toPath());trace=new FileOutputStream(new File(root,"events.jsonl"),true);
            report("新一轮启动，重新识别当前关卡");event("started");
            report("准备标准识别视图");camera.apply();launch();
            board=new Board(enterBoard(null));
            scoutProgress=new ScoutProgress(board.level);
            save("initial.png");event("initial");
            while(true){checkStop();try{int target=board.attack();
                if(target>=0){attack(target);continue;}
                Strategy strategy=new Strategy(board.grid.n,board.known,board.fleet,board.sunk,board.completed);
                target=strategy.certain(board.known);
                if(target>=0){board.known[target]=Vision.SHIP;event("deduced");attack(target);continue;}
                target=strategy.scout(board.known);
                if(target<0)throw new Board.ReviewNeeded("棋盘位置已读完但没有看到胜利，停止检查实际结果");
                scout(target);
            }catch(Board.ReviewNeeded e){recheck(e);}
            }
        }catch(Stopped e){outcome="stopped";phase=e.getMessage();}
        catch(Exception e){outcome="error";phase=e.getMessage();if(latest!=null)save("error.png");throw e;}
        finally{
            stopping=true;
            try{
                // Killing the app alone leaves kernel TCP send queues alive.
                // Abort its sockets while packets are still blocked, then stop it.
                if(network.offline||network.ipv4||network.ipv6){network.disposeConnections();Device.shell("am force-stop "+pkg);}
                network.restore();
                if(camera.installed){Device.shell("am force-stop "+pkg);camera.restore();}
            }catch(Exception cleanup){outcome="error";phase+="；收尾未完成: "+cleanup.getMessage();}
            report(phase);event("finished");if(trace!=null)trace.close();if(latest!=null)latest.recycle();
        }
    }
    void launch()throws Exception{
        Device.shell("am force-stop "+pkg);
        String resolved=Device.shell("cmd package resolve-activity --brief "+pkg).trim();
        String activity=resolved.substring(resolved.lastIndexOf('\n')+1);if(!activity.startsWith(pkg+"/"))throw new IOException("游戏启动入口未解析");
        Device.shell("am start -n "+activity);
    }
    Vision.Observation observe(Vision.Grid geometry)throws Exception{
        return observe(geometry,geometry!=null?confirmedOpened():null);
    }
    boolean[] confirmedOpened(){
        if(board==null)return null;
        boolean[] opened=new boolean[board.known.length];for(int i=0;i<opened.length;i++)opened[i]=board.confirmed[i]!=Vision.UNKNOWN;return opened;
    }
    Vision.Observation observe(Vision.Grid geometry,boolean[] opened)throws Exception{
        if(latest!=null)latest.recycle();latest=device.capture();frames++;int[] pixels=new int[latest.getWidth()*latest.getHeight()];latest.getPixels(pixels,0,latest.getWidth(),0,0,latest.getWidth(),latest.getHeight());
        frame=new Vision.Frame(latest.getWidth(),latest.getHeight(),pixels);Vision.Observation o=Vision.read(frame,geometry,opened);
        // Locate the main client's visible entrance.
        if(o.page.equals("unknown")&&Vision.homeEntryMain(frame)!=null)o.page="home";
        if(o.page.equals("board"))digits.fill(frame,o);return o;
    }
    interface Condition {boolean matches(Vision.Observation o);}
    Vision.Observation await(String what,Vision.Grid grid,Condition condition,long timeout)throws Exception{
        return await(what,grid,condition,timeout,grid!=null?confirmedOpened():null);
    }
    Vision.Observation await(String what,Vision.Grid grid,Condition condition,long timeout,boolean[] opened)throws Exception{
        long end=SystemClock.elapsedRealtime()+timeout;
        // The complete offline board can appear only briefly between the entry
        // clouds and victory overlay. Capture continuously during that action.
        while(true){checkStop();Vision.Observation o=observe(grid,opened);if(condition.matches(o))return o;if(SystemClock.elapsedRealtime()>end)throw new WaitExpired(what+"未确认；当前页面="+o.page);if(!network.offline)SystemClock.sleep(100);}
    }
    Vision.Observation enterBoard(Vision.Grid geometry)throws Exception{
        try{return enteredBoard(enterBoardOnce(geometry));}
        catch(WaitExpired first){
            Vision.Observation current=observe(geometry);
            if(!current.page.equals("home"))throw first;
            // A still-visible entrance permits navigation only. Keep the shot
            // journal, network isolation and resource baseline untouched.
            if(Vision.homeEntryMain(frame)==null)
                throw new IOException("回到主页后未找到声呐入口；保留原投弹记录",first);
            report("主页入口未打开，重新定位后进入声呐");event("navigation_recovery_started");
            try{
                Vision.Observation recovered=enteredBoard(enterBoardOnce(geometry));
                save("navigation-recovered.png");event("navigation_recovered");return recovered;
            }catch(WaitExpired second){
                // A navigation failure must not fall into the shot-only review,
                // which cannot navigate from home and would only wait again.
                throw new IOException("主页重入声呐未成功："+second.getMessage(),second);
            }
        }
    }
    Vision.Observation enteredBoard(Vision.Observation observation){
        // Re-entering the same board is not progress. Consume a victory only
        // after its following board is ready, including the reconnect path.
        if(nextLevelPending&&observation.page.equals("board")){
            scoutProgress.enteredNextLevel();nextLevelPending=false;
        }
        return observation;
    }
    Vision.Observation enterBoardOnce(Vision.Grid geometry)throws Exception{
        report("进入声呐");
        Vision.Observation o=await("登录或活动页面",geometry,x->!x.page.equals("unknown"),30000);
        for(int step=0;step<4;step++){
            if(o.page.equals("board")){o=settledView(geometry,o);if(o.page.equals("board"))return o;}
            if(o.page.equals("victory")&&!network.offline){
                continueVictory();
                o=await("下一海域页面",null,x->!x.page.equals("unknown"),30000);
                geometry=null;continue;
            }
            if(o.page.equals("connection"))throw new IOException("仍处于连接中断页面");
            if(!o.page.equals("login")&&!o.page.equals("home")&&!o.page.equals("events"))throw new IOException("进入声呐时遇到未处理页面: "+o.page);
            final String before=o.page;
            int tapX=o.targetX,tapY=o.targetY;
            if(before.equals("home")){
                final int[] previous={-1,-1};
                await("主界面声呐入口停止移动",null,x->{
                    Vision.Blob label=x.page.equals("home")?Vision.homeEntryMain(frame):null;
                    if(label==null){previous[0]=previous[1]=-1;return false;}
                    int cx=(int)label.x(),cy=label.y0+label.h()/2;boolean still=cx==previous[0]&&cy==previous[1];previous[0]=cx;previous[1]=cy;return still;
                },15000);
                tapX=previous[0];tapY=previous[1];
            }
            Bitmap entrance=latest.copy(Bitmap.Config.ARGB_8888,false);
            try{
                event("navigation_tap",new JSONObject().put("page",before).put("x",tapX).put("y",tapY));
                tap(tapX,tapY);
                o=await("离开"+before,geometry,x->!x.page.equals("unknown")&&!x.page.equals(before),15000);
            }catch(WaitExpired failure){
                save(entrance,"navigation-before.png");save("navigation-after.png");throw failure;
            }finally{entrance.recycle();}
        }if(o.page.equals("board")){o=settledView(geometry,o);if(o.page.equals("board"))return o;}
        throw new IOException("没有进入声呐活动");
    }
    Vision.Observation settledView(Vision.Grid geometry,Vision.Observation first)throws Exception{
        // Offline reveals may immediately lead to a provisional victory overlay.
        // Read that board now; its visible tiles register the existing geometry.
        if(network.offline){
            // The board can show through the entry clouds before the fleet
            // sidebar does. Partial icons lose completion information used to
            // remove raised-hull occlusion; do not ingest that partial read.
            if(java.util.Arrays.equals(first.fleet,board.fleet))return first;
            return await("完整舰队栏",geometry,o->o.page.equals("board")&&java.util.Arrays.equals(o.fleet,board.fleet),15000);
        }
        // The activity's camera pans after its controls appear. Calibrate/read only
        // when the visible tile boundaries stop moving, not on the first board frame.
        final String[] previous={first.outline};
        Vision.Observation settled=await("棋盘镜头停止移动",geometry,o->{
            // Reconnect can briefly show the old board before returning home.
            // Hand a recognized page back to enterBoard's existing navigation.
            if(!o.page.equals("board")){previous[0]="";return !o.page.equals("unknown");}
            boolean same=o.outline.equals(previous[0]);previous[0]=o.outline;return same;
        },15000);
        if(!settled.page.equals("board")&&!settled.page.equals("victory"))report("棋盘过渡到 "+settled.page+"，继续处理当前页面");
        return settled;
    }
    boolean blueScout(){
        boolean preferBlue=config.optString("scoutMode","Red").equals("Blue");
        return scoutProgress==null?preferBlue:scoutProgress.blue(preferBlue);
    }
    void refreshScoutMode()throws Exception{
        JSONObject latestConfig=new JSONObject(new String(Files.readAllBytes(new File(root,"run.json").toPath()),StandardCharsets.UTF_8));
        String mode=latestConfig.optString("scoutMode","Red");
        if(!mode.equals(config.optString("scoutMode","Red"))){
            config.put("scoutMode",mode);report(blueScout()?"已更新侦察偏好，当前使用蓝弹离线侦察":"已更新侦察偏好，当前使用红弹离线侦察");event("mode_changed");
        }
    }
    Vision.Observation requireAmmo(boolean blue)throws Exception{
        // Display reads from previous attacks are not a new scout's baseline.
        Vision.Observation fresh=observe(board.grid);
        if((blue?fresh.blue:fresh.red)<0)fresh=recheckAmmo((blue?"蓝":"红")+"弹读数不完整，未投弹",blue,-1);
        if((blue?fresh.blue:fresh.red)<=0)throw new Stopped((blue?"蓝":"红")+"弹不足");
        return fresh;
    }
    void tap(int x,int y)throws Exception{tap(x,y,false);}
    void tap(int x,int y,boolean bomb)throws Exception{
        checkStop();if(!Device.foreground(pkg))throw new IOException("前台已离开目标游戏，停止触控");
        // Never issue a new bomb after
        // STOP, but allow an issued action's observation/recovery to finish.
        if(bomb){if(stopRequested())throw new Stopped("用户停止");actionInFlight=true;}
        device.tap(x,y);
    }
    void aim(int cell)throws Exception{double[] xy=board.grid.xy(cell/board.grid.n,cell%board.grid.n);tap((int)Math.round(xy[0]),(int)Math.round(xy[1]),true);}
    void journal(String kind,int cell)throws Exception{
        unresolved=new JSONObject().put("package",pkg).put("kind",kind).put("cell",cell).put("redBefore",board.red).put("blueBefore",board.blue).put("level",board.level).put("createdAt",System.currentTimeMillis());
        atomic(pending,unresolved.toString());actionReviewUsed=resourceReviewUsed=false;event("attempt_"+kind);
    }
    void settled()throws Exception{Files.deleteIfExists(pending.toPath());unresolved=null;reviewUsed=actionReviewUsed=resourceReviewUsed=false;}
    void scout(int cell)throws Exception{
        refreshScoutMode();
        boolean blue=blueScout();
        final String kind=blue?"blue_scout":"red";
        Vision.Observation before=requireAmmo(blue);
        final int baseline=blue?before.blue:before.red;
        board.grid=before.grid;board.blue=before.blue;board.red=before.red;
        if(board.refreshCompleted(before)){
            report("已补记完成艇及周边水格，重新选择目标");event("scout_replanned");return;
        }
        report((blue?"离线蓝侦 ":"离线红侦 ")+coordinate(cell));
        network.isolate();tap(blue?1119:1218,655);journal(kind,cell);aim(cell);if(blue)blueActions++;else redActions++;
        // Exit/re-enter before reading scouting results. No red decrement prerequisite.
        Vision.Observation scouted;
        // The close button is locked while an offline shot is pending. Android
        // Back follows the client's activity-return path and retains that shot.
        try{
            checkStop();if(!Device.foreground(pkg))throw new IOException("前台已离开目标游戏，停止返回");device.back();await("退出声呐",null,o->o.page.equals("home"),15000);scouted=enterBoard(board.grid);
            if(!blue){
                SystemClock.sleep(300);
                scouted=observe(board.grid);
                if(!scouted.page.equals("board"))throw new IOException("红侦等待后未识别到棋盘：页面="+scouted.page);
            }
        }
        catch(WaitExpired e){scouted=recheckAction(before,cell,kind,e);}
        board.scout(scouted);save("scout.png");event("scout_observed");
        report("恢复通信");network.disposeConnections();
        Vision.Observation disconnected=await("连接中断提示",null,o->o.page.equals("connection"),15000);
        network.restore();tap(disconnected.targetX,disconnected.targetY);
        await("离开连接提示",null,o->!o.page.equals("unknown")&&!o.page.equals("connection"),30000);
        Vision.Observation restored=enterBoard(board.grid);
        int read=blue?restored.blue:restored.red;
        if(read!=baseline)restored=recheckAmmo((blue?"蓝侦后蓝弹":"红侦后红弹")+"没有恢复到原基线：原值"+baseline+"，现值"+read,blue,baseline);
        board.online(restored);settled();save("restored.png");event(blue?"blue_scout_reconciled":"red_reconciled");
        finishAction();
    }
    void attack(int cell)throws Exception{
        report("蓝弹攻击 "+coordinate(cell));
        Vision.Observation before=observe(board.grid);
        if(!before.page.equals("board"))throw new Board.ReviewNeeded("蓝弹前未识别到棋盘，停止核对画面：页面="+before.page);
        if(!before.covered[cell])throw new Board.ReviewNeeded("蓝弹目标没有可见盖板，停止核对棋盘："+coordinate(cell));
        board.grid=before.grid;
        tap(1119,655);journal("blue",cell);aim(cell);blueActions++;
        final int[] last={Vision.UNKNOWN};
        Vision.Observation result;
        try{result=await("蓝弹目标格变化",board.grid,o->{
            if(o.page.equals("victory"))return true;
            int seen=Vision.attackResult(before,o,cell);
            boolean stable=seen!=Vision.UNKNOWN&&seen==last[0];last[0]=seen;return stable;
        },15000);}catch(WaitExpired e){result=recheckAction(before,cell,"blue",e);}
        finishAttack(cell,before,result);
    }
    void finishAttack(int cell,Vision.Observation before,Vision.Observation result)throws Exception{
        if(result.page.equals("victory")){nextLevel();finishAction();return;}
        int observed=Vision.attackResult(before,result,cell);
        if(observed==Vision.SHIP&&result.cells[cell]!=Vision.SHIP)System.out.println("侧栏新增击沉，确认本次蓝弹命中 "+coordinate(cell));
        result.cells[cell]=observed;
        // The hit flash also covers unrelated cells. This acknowledgement proves
        // only the targeted cell, not ammunition consumption or unrelated cells.
        board.known[cell]=result.cells[cell];board.saved[cell]=true;board.blue=result.blue;board.red=result.red;
        board.confirmed[cell]=observed;
        board.completed=result.completed.clone();
        board.rememberSunk(result);
        board.completedWater(result);
        settled();save("blue.png");event(result.cells[cell]==Vision.SHIP?"blue_observed":"blue_miss");
        finishAction();
        if(result.cells[cell]!=Vision.SHIP)throw new Board.ReviewNeeded("蓝弹目标显示水格；停止检查识别或求解");
        // Completion is based on the victory screen, not a local cell count.
        if(allAttacksVisible()){
            await("胜利画面",board.grid,o->o.page.equals("victory"),15000);nextLevel();
        }
    }
    boolean allAttacksVisible(){int total=0,hits=0;for(int length:board.fleet)total+=length;for(int i=0;i<board.known.length;i++)if(board.known[i]==Vision.SHIP&&board.saved[i])hits++;return hits==total;}
    // Shared only by error paths. Stable pixels supply evidence; no input is sent.
    Vision.Observation reviewView(String what,int resource)throws Exception{
        boolean[] opened=new boolean[board.known.length];for(int i=0;i<opened.length;i++)opened[i]=board.confirmed[i]!=Vision.UNKNOWN;
        final Vision.Observation[] previous={null};
        return await(what,board.grid,o->{
            if(resource==0&&o.page.equals("victory"))return true;
            Vision.Observation p=previous[0];previous[0]=o;
            return o.page.equals("board")&&java.util.Arrays.equals(o.fleet,board.fleet)&&p!=null
                &&o.outline.equals(p.outline)&&java.util.Arrays.equals(o.cells,p.cells)&&java.util.Arrays.equals(o.completed,p.completed)
                &&(resource==0||(resource==1?o.red==p.red:o.blue==p.blue));
        },15000,opened);
    }
    Vision.Observation recheckAction(Vision.Observation before,int cell,String kind,WaitExpired cause)throws Exception{
        checkStop();
        boolean scout=kind.equals("red")||kind.equals("blue_scout");
        if(actionReviewUsed||!actionInFlight||!pending.exists()||unresolved==null||!kind.equals(unresolved.optString("kind"))||unresolved.optInt("cell",-1)!=cell)throw cause;
        actionReviewUsed=true;report("投弹结果复核："+cause.getMessage());save("action-review-before.png");event("action_review_started");
        Vision.Observation fresh=reviewView("投弹结果复核画面",0);
        boolean confirmed=false;
        if(before!=null&&before.page.equals("board")&&before.grid.n==board.grid.n){
            if(scout&&fresh.page.equals("board")){
                for(int i=0;i<before.cells.length;i++)if(before.covered[i]&&!fresh.covered[i]&&fresh.cells[i]!=Vision.UNKNOWN){confirmed=true;break;}
            }else if(!scout&&!network.offline&&before.covered[cell])
                confirmed=fresh.page.equals("victory")||Vision.attackResult(before,fresh,cell)!=Vision.UNKNOWN;
        }
        // An offline victory overlay cannot replace the lost scouting picture.
        if(!confirmed)throw new IOException(cause.getMessage()+"；一次复核仍无法确认本次投弹，保留未确认记录");
        save("action-review-after.png");event("action_review_confirmed");return fresh;
    }
    Vision.Observation recheckAmmo(String cause,boolean blue,int expected)throws Exception{
        checkStop();
        if(resourceReviewUsed||network.offline||network.ipv4||network.ipv6)throw new IOException(cause);
        resourceReviewUsed=true;report("资源读数复核："+cause);save("resource-review-before.png");event("resource_review_started");
        Vision.Observation fresh=reviewView("资源读数复核画面",blue?2:1);int value=blue?fresh.blue:fresh.red;
        if(value<0||expected>=0&&value!=expected)
            throw new IOException(cause+"；一次复核读数="+value+(expected>=0?"，保留原基线="+expected:"")+"，停止处理");
        // Only a missing read may be filled. An existing baseline is never reset.
        save("resource-review-after.png");event("resource_review_confirmed");return fresh;
    }
    void recheck(Board.ReviewNeeded cause)throws Exception{
        checkStop();
        // Only settled board errors enter here; never replay or abandon a shot.
        if(reviewUsed||actionInFlight||pending.exists()||network.offline||network.ipv4||network.ipv6)throw cause;
        reviewUsed=true;report("故障复核："+cause.getMessage());save("review-before.png");event("review_started");
        try{
            Vision.Observation fresh=reviewView("故障复核画面",0);
            if(fresh.page.equals("victory")){report("复核确认已胜利，进入下一关");event("review_victory");nextLevel();return;}
            Board candidate=board.reread(fresh);int changed=0,withdrawn=0,revised=0;
            for(int i=0;i<board.known.length;i++){
                if(candidate.known[i]!=board.known[i]||candidate.saved[i]!=board.saved[i])changed++;
                if(board.known[i]!=Vision.UNKNOWN&&candidate.known[i]==Vision.UNKNOWN)withdrawn++;
                if(board.confirmed[i]!=candidate.confirmed[i])revised++;
            }
            checkStop();board=candidate;save("review-after.png");report("复核通过：更新 "+changed+" 格，撤回 "+withdrawn+" 格未确认旧读数，修正 "+revised+" 格旧投弹视觉结论，继续运行");event("review_corrected");
        }catch(Stopped e){throw e;}
        catch(Exception e){throw new IOException(cause.getMessage()+"；一次复核未通过："+e.getMessage(),e);}
    }
    void continueVictory()throws Exception{
        report("已通关，等待继续提示");
        await("胜利界面继续提示",null,o->o.page.equals("victory")&&Vision.fraction(frame,550,528,740,552,Vision.WHITE)>0.05,15000);
        save("continue-ready.png");event("continue_ready");
        report("已通关，进入下一关");tap(640,540);
        await("离开胜利画面",null,o->!o.page.equals("victory")&&!o.page.equals("unknown"),15000);
        if(scoutProgress!=null)nextLevelPending=true;
    }
    void nextLevel()throws Exception{
        levels++;save("victory.png");event("victory");continueVictory();
        Vision.Observation next=enterBoard(null);
        settled();
        board=new Board(next);save("next-level.png");event("next_level");
        int target=config.optInt("stopAfterLevels",0);if(target>0&&levels>=target)throw new Stopped("已完成 "+levels+" 关并进入下一关");
    }
    String coordinate(int i){return "("+(i/board.grid.n+1)+","+(i%board.grid.n+1)+")";}
    boolean stopRequested(){if(stop.exists())stopPending=true;return stopPending;}
    void checkStop()throws Stopped{if(stopRequested()&&!stopping&&!actionInFlight)throw new Stopped("用户停止");}
    void finishAction()throws Stopped{actionInFlight=false;checkStop();}
    void save(String name)throws Exception{save(latest,name);}
    void save(Bitmap image,String name)throws Exception{
        if(image==null)return;device.save(image,name);
        if(name.contains("review-")||name.startsWith("navigation-")){
            File history=new File(root,"history");history.mkdirs();
            Files.copy(new File(root,name).toPath(),new File(history,System.currentTimeMillis()+"-sea"+(board==null?0:board.level)+"-"+name).toPath());
        }
    }
    JSONObject status()throws Exception{
        JSONObject o=new JSONObject().put("state",outcome).put("phase",phase).put("target","International").put("package",pkg).put("scoutMode",config.optString("scoutMode","Red")).put("elapsedMs",SystemClock.elapsedRealtime()-started).put("levels",levels).put("blueActions",blueActions).put("redActions",redActions).put("frames",frames).put("offline",network.offline).put("pending",pending.exists());
        if(board!=null)o.put("level",board.level).put("size",board.grid.n).put("red",board.red).put("blue",board.blue).put("board",board.text());
        if(scoutProgress!=null)o.put("effectiveScoutMode",blueScout()?"Blue":"Red").put("redScoutUnlocked",!scoutProgress.blue(false));
        if(Vision.model!=null)o.put("modelCells",Vision.model.calls).put("modelMs",Vision.model.nanos/1000000.0);return o;
    }
    void report(String phase)throws Exception{this.phase=phase;atomic(new File(root,"status.json"),status().toString());System.out.println(phase);}
    void reject(String message)throws Exception{outcome="error";report(message);throw new IOException(message);}
    void event(String kind)throws Exception{event(kind,null);}
    void event(String kind,JSONObject detail)throws Exception{if(trace!=null){JSONObject o=status().put("event",kind).put("time",System.currentTimeMillis());if(detail!=null)o.put("detail",detail);if(board!=null)o.put("grid",new JSONArray(board.grid.h)).put("completed",new JSONArray(board.completed));trace.write((o.toString()+"\n").getBytes(StandardCharsets.UTF_8));trace.flush();}}
    static void atomic(File path,String text)throws Exception{File temp=new File(path.getPath()+".new");try(FileOutputStream out=new FileOutputStream(temp)){out.write(text.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!temp.renameTo(path))throw new IOException("状态文件写入失败");}
    static final class Stopped extends Exception {Stopped(String message){super(message);}}
    static final class WaitExpired extends IOException {WaitExpired(String message){super(message);}}
}
