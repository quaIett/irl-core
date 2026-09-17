import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import org.qualet.irl.light.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;

/** Tests the actual registry, CPU snapshots, packed order and GPU tail layout. */
public final class ProfileRegistryGlTest
{
    static final int OFFSET=16+2048*96, TARGET=OFFSET+2048*112;
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void light(long id, float x){LightRegistry.registerPoint(x,0,0,1,1,1,1,10,false,false,.4F,.05F,1,0,false,id);}
    static ByteBuffer read(){int id=glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,7);glBindBuffer(GL_SHADER_STORAGE_BUFFER,id);ByteBuffer b=MemoryUtil.memAlloc(glGetBufferParameteri(GL_SHADER_STORAGE_BUFFER,GL_BUFFER_SIZE));glGetBufferSubData(GL_SHADER_STORAGE_BUFFER,0,b);glBindBuffer(GL_SHADER_STORAGE_BUFFER,0);return b;}
    public static void main(String[] args)throws Exception
    {
        check(glfwInit(),"GLFW");glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,3);
        long window=glfwCreateWindow(32,32,"Profiles test",0,0);glfwMakeContextCurrent(window);GL.createCapabilities();
        try {
            for(int life=0;life<3;life++){
                LightRegistry.clear();LightBuffer.delete();LightRegistry.setUploadCap(0);
                LightProfile p=new LightProfile();p.customVl=true;p.customOutline=true;p.selectedReplays=true;
                p.selectedLightReplays=true;p.lightReplayIds=new int[]{71,73,0};
                p.intensity=.25F;p.strength=1.7F;p.replayIds=new int[3000];for(int k=0;k<p.replayIds.length;k++)p.replayIds[k]=k+1;
                light(20,20);LightRegistry.setProfile(20,p);p.intensity=4;p.replayIds[0]=9999;p.lightReplayIds[1]=83;
                light(10,2);LightRegistry.setProfile(10,p);
                LightRegistry.prioritize(0,0,0);LightRegistry.flush();
                check(glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING)==0,"generic binding restored");
                ByteBuffer b=read();try{
                    check(b.getInt(0)==2&&b.getInt(12)==0x49524C50,"profile header");
                    for(int i=0;i<2;i++){
                        boolean first=b.getFloat(16+i*96)==20;
                        int o=OFFSET+i*112;check(b.getFloat(o)==(first?.25F:4F),"profile follows priority and snapshot");
                        check(b.getInt(o+96)==15,"independent flags");check(b.getInt(o+104)==3000,"unbounded selection upload");
                        int t=TARGET+4*b.getInt(o+100);
                        for(int k=0;k<3000;k++)check(b.getInt(t+k*4)==(!first&&k==0?9999:k+1),"target snapshot");
                        check(b.getInt(o+108)==3,"light selection count");
                        check(b.getInt(t+12000)==71&&b.getInt(t+12004)==(first?73:83)&&b.getInt(t+12008)==0,"light target snapshot and layout");
                    }
                }finally{MemoryUtil.memFree(b);}
                // Dedup overwrite back to a legacy registration must clear prior effects.
                light(10,1);LightRegistry.setProfile(10,p);light(10,3);LightRegistry.flush();
                b=read();try{check(b.getInt(0)==1&&b.getInt(OFFSET+96)==0,"dedup drops profile on legacy overwrite");}finally{MemoryUtil.memFree(b);}
                // Pending shadow/cap compaction cannot move a profile onto a different source.
                LightRegistry.setUploadCap(1);light(1,100);LightRegistry.setProfile(1,p);light(2,1);LightRegistry.setShadowPending(0);LightRegistry.prioritize(0,0,0);LightRegistry.flush();
                b=read();try{check(b.getInt(0)==1&&b.getFloat(16)==1&&b.getInt(OFFSET+96)==0,"cap and pending alignment");}finally{MemoryUtil.memFree(b);}
                LightRegistry.setUploadCap(0);p.intensity=Float.NaN;p.noiseSpeed=.38F;p.noiseMorph=Float.POSITIVE_INFINITY;p.fresnel=-1;p.steps=999;p.pixelSize=-5;
                light(3,1);LightRegistry.setProfile(3,p);LightRegistry.flush();
                b=read();try{check(b.getFloat(OFFSET)==1,"NaN intensity");check(b.getFloat(OFFSET+24)==.38F,"smooth wind values are not quantized");check(b.getFloat(OFFSET+48)==0,"infinite morph");check(b.getInt(OFFSET+32)==96,"step clamp");check(b.getFloat(OFFSET+68)==.001F,"fresnel clamp");check(b.getFloat(OFFSET+84)==1,"pixel clamp");}finally{MemoryUtil.memFree(b);}
                p.selectedReplays=false;light(4,1);LightRegistry.setProfile(4,p);LightRegistry.flush();
                b=read();try{check(b.getInt(OFFSET+104)==0&&b.getInt(OFFSET+108)==3,"independent light-only count");check(b.getInt(TARGET)==71,"light-only targets start at current offset");}finally{MemoryUtil.memFree(b);}
                LightBuffer.uploadEmpty();b=read();try{check(b.getInt(0)==0,"empty flush");}finally{MemoryUtil.memFree(b);}
                check(glGetError()==GL_NO_ERROR,"GL error free");
            }
            Files.createDirectories(Path.of(args[0]));Files.writeString(Path.of(args[0],"profiles-registry.json"),"{\"passed\":true,\"checks\":"+checks+"}");
            System.out.println("Profile registry/GPU PASS: "+checks+" checks");
        }finally{LightBuffer.delete();ClusterGridBuffer.delete();glfwDestroyWindow(window);glfwTerminate();}
    }
}
