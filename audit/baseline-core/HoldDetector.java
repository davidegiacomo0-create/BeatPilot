package it.dave.beatpilot.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The hold glyph is a narrow stem with a wide foot and a small shaded end cap. */
public final class HoldDetector {
    private static final double[] SIDES = {-.32,-.26,.26,.32};
    private int[] white = new int[0], pale = new int[0];
    private boolean[] stem = new boolean[0];
    private double[] widths = new double[0], centers = new double[0];
    private final int[] shaftSamples = new int[13];

    public List<Detector.Detection> detect(GrayFrame f, double[] lanes, double line) {
        List<Detector.Detection> result = new ArrayList<>();
        int top = Math.max(0,(int)((line-.40)*f.height));
        int bottom = Math.min(f.height,(int)((line+.11)*f.height));
        if (white.length < f.height) {
            white=new int[f.height]; pale=new int[f.height]; stem=new boolean[f.height];
            widths=new double[f.height]; centers=new double[f.height];
        }
        for (int lane=0;lane<lanes.length;lane++) {
            for (int y=top;y<bottom;y++) {
                double scale=1+BeatstarDetector.PERSPECTIVE*(y/(double)f.height-line);
                widths[y]=.285*f.width*scale;
                centers[y]=(.5+(lanes[lane]-.5)*scale)*f.width;
                int left=(int)(centers[y]-.22*widths[y]), right=(int)(centers[y]+.22*widths[y]);
                white[y]=count(f,y,left,right,225); pale[y]=count(f,y,left,right,150);
                int sides=0;
                for (double x : SIDES)
                    if (f.at((int)(centers[y]+x*widths[y]),y)<140) sides++;
                stem[y]=white[y]>=Math.max(3,widths[y]*.025) && white[y]<=widths[y]*.15 && sides>=3;
            }
            int start=-1, last=-1;
            for (int y=top;y<=bottom;y++) {
                if (y<bottom && stem[y]) { if (start<0) start=y; last=y; }
                if (start<0 || (y<bottom && y-last<=2)) continue;
                if (last-start>=Math.max(28,widths[start]*.28)) inspect(f,lane,line,top,bottom,start,last,result);
                start=-1;
            }
        }
        return result;
    }

    private void inspect(GrayFrame f,int lane,double line,int top,int bottom,int first,int last,
                         List<Detector.Detection> result) {
        // Locate the wide horizontal foot immediately after the thin stem.
        int base=-1, end=-1; double mass=0, moment=0;
        for (int y=last+1;y<Math.min(bottom,last+1+(int)(f.height*.021));y++) {
            int ink=count(f,y,(int)(centers[y]-.37*widths[y]),(int)(centers[y]+.37*widths[y]),225);
            if (ink>widths[y]*.38) {
                if (base<0) base=y;
                end=y; mass+=ink; moment+=(y+.5)*ink;
            } else if (base>=0 || y-last>5) break;
        }
        boolean hasFoot=false;
        if (base>=0 && end-base>=2 && end-base<f.height*.018) {
            boolean clear=true;
            for (int y=end+3;y<=Math.min(bottom-1,end+8);y++) if (white[y]>widths[y]*.02) clear=false;
            if (clear) {
                hasFoot=true;
                result.add(new Detector.Detection(lane,Kind.HOLD_START,moment/mass/f.height,.98,
                        (end-first+1)/(double)f.height));
            }
        }

        // A tail remains recognisable after its wide foot has left the camera region.
        // The shaded, wider cap distinguishes it from an arrow stem or a debug line.
        int cap=-1, peak=0, whiteCap=0, above=0;
        int limit=Math.max(top,first-Math.max(8,(int)(f.height*.015)));
        for (int y=first-1;y>=limit;y--) {
            if (pale[y]>=Math.max(3,widths[y]*.025)) {
                cap=y; peak=Math.max(peak,pale[y]); whiteCap=Math.max(whiteCap,white[y]); above=0;
            } else if (++above>=3) break;
        }
        int stemWidth=white[Math.min(last,first+5)];
        if (cap>top+3 && first-cap>=3 && above>=3 && peak>=stemWidth*1.18
                && peak<=widths[first]*.19 && whiteCap<stemWidth*.75) {
            result.add(new Detector.Detection(lane,Kind.HOLD_END,(cap+.5)/f.height,.96,
                    (last-cap+1)/(double)f.height));
        } else if (hasFoot && above>=3 && brightCap(first,last)) {
            // Stage lighting can make the cap as white as the shaft. It then belongs
            // to the stem run itself, so the shaded-cap search above cannot see it.
            // Require the confirmed horizontal foot too, a short wider tip followed
            // by a narrower shaft, and clear space above. This excludes menu lettering
            // and arrow tips. Tracker retains this end before the pressed note changes.
            int tip=cap>=0 ? cap : first;
            if (tip>top+3 && first-tip<=6)
                result.add(new Detector.Detection(lane,Kind.HOLD_END,(tip+.5)/f.height,.96,
                        (last-tip+1)/(double)f.height,true));
        }
    }

    private boolean brightCap(int first,int last) {
        if (last-first<28) return false;
        for (int i=0;i<shaftSamples.length;i++) shaftSamples[i]=white[first+12+i];
        Arrays.sort(shaftSamples);
        int shaft=shaftSamples[shaftSamples.length/2], peak=0, wideRows=0;
        if (shaft<Math.max(3,widths[first]*.025) || shaft>widths[first]*.12) return false;
        for (int y=first;y<first+10;y++) {
            peak=Math.max(peak,white[y]);
            if (white[y]>=shaft*1.25) wideRows++;
        }
        return wideRows>=3 && peak<=widths[first]*.19;
    }

    private int count(GrayFrame f,int y,int left,int right,int threshold) {
        int count=0;
        for (int x=Math.max(0,left);x<=Math.min(f.width-1,right);x++) if (f.at(x,y)>threshold) count++;
        return count;
    }
}
