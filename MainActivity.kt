package com.bassflashstudio

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class MainActivity : Activity() {
    private var audio: Uri? = null
    private lateinit var pick: Button
    private lateinit var make: Button
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var resolution: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 28, 24, 24); setBackgroundColor(Color.rgb(12,12,12))
        }
        fun label(s: String, sp: Float) = TextView(this).apply {
            text=s; textSize=sp; setTextColor(Color.WHITE); gravity=Gravity.CENTER
        }
        box.addView(label("BASS FLASH STUDIO",25f))
        box.addView(label("Bas gücüne göre yumuşak siyah / beyaz video",14f))
        pick=Button(this).apply { text="Müzik seç"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type="audio/*"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },100)
        }}
        box.addView(pick)
        resolution=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,
                listOf("720p • 1280×720","1080p • 1920×1080"))
        }
        box.addView(resolution)
        make=Button(this).apply { text="MP4 oluştur"; isEnabled=false; setOnClickListener { export() } }
        box.addView(make)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100 }
        box.addView(progress)
        status=label("Önce bir müzik dosyası seç.",14f); box.addView(status)
        box.addView(label("35–150 Hz • 60 FPS • MP4 H.264 + AAC",12f))
        setContentView(box)
    }

    @Deprecated("legacy document picker callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==100 && resultCode==RESULT_OK) {
            audio=data?.data
            audio?.let { try { contentResolver.takePersistableUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_:Exception){} }
            status.text=if(audio!=null) "Müzik seçildi." else "Dosya seçilemedi."
            make.isEnabled=audio!=null
        }
    }

    private fun export() {
        val uri=audio ?: return
        val (w,h)=if(resolution.selectedItemPosition==0) 1280 to 720 else 1920 to 1080
        make.isEnabled=false; pick.isEnabled=false; progress.progress=0
        Thread {
            try {
                val dir=File(cacheDir,"bassflash_work").apply { mkdirs() }
                val silent=File(dir,"silent.mp4"); val aac=File(dir,"audio.m4a"); val final=File(dir,"final.mp4")
                val engine=ExportEngine(this,uri,w,h) { p,msg -> runOnUiThread { progress.progress=p; status.text=msg } }
                engine.makeSilentVideo(silent)
                engine.makeAacAudio(aac)
                engine.mux(silent,aac,final)
                val values=ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME,"BassFlash_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE,"video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/BassFlashStudio")
                }
                val dest=contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values)
                    ?: error("Galeri kayıt alanı açılamadı.")
                contentResolver.openOutputStream(dest)?.use { out -> final.inputStream().use { it.copyTo(out) } }
                dir.deleteRecursively()
                runOnUiThread {
                    progress.progress=100; status.text="Bitti! Galeri > Movies > BassFlashStudio"
                    make.isEnabled=true; pick.isEnabled=true
                    Toast.makeText(this,"MP4 galeriye kaydedildi",Toast.LENGTH_LONG).show()
                }
            } catch(e:Exception) {
                runOnUiThread { status.text="Hata: ${e.message}"; make.isEnabled=true; pick.isEnabled=true }
            }
        }.start()
    }
}

private class ExportEngine(
    private val activity:Activity, private val uri:Uri, private val width:Int, private val height:Int,
    private val report:(Int,String)->Unit
) {
    private val fps=60
    private val rate=44100
    private val ch=2
    private var pcm=ShortArray(0)
    private var durationUs=0L
    private var envelope=FloatArray(0)

    // Decode selected audio to PCM, then calculate a bass-only 35–150 Hz envelope.
    private fun decodeAudio() {
        val ex=MediaExtractor()
        ex.setDataSource(activity,uri,null)
        var track=-1
        for(i in 0 until ex.trackCount) if(ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true) { track=i; break }
        require(track>=0) { "Ses akışı bulunamadı." }
        ex.selectTrack(track)
        val fmt=ex.getTrackFormat(track)
        val dec=MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(fmt,null,null,0); dec.start()
        val all=java.io.ByteArrayOutputStream()
        val info=MediaCodec.BufferInfo()
        var inputDone=false; var outputDone=false
        while(!outputDone) {
            if(!inputDone) {
                val ix=dec.dequeueInputBuffer(10000)
                if(ix>=0) {
                    val b=dec.getInputBuffer(ix)!!; b.clear()
                    val n=ex.readSampleData(b,0)
                    if(n<0) { dec.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true }
                    else { dec.queueInputBuffer(ix,0,n,ex.sampleTime,0); ex.advance() }
                }
            }
            val oi=dec.dequeueOutputBuffer(info,10000)
            if(oi>=0) {
                val b=dec.getOutputBuffer(oi)!!
                if(info.size>0) { b.position(info.offset); b.limit(info.offset+info.size); val tmp=ByteArray(info.size); b.get(tmp); all.write(tmp) }
                dec.releaseOutputBuffer(oi,false)
                if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0) outputDone=true
            }
        }
        dec.stop(); dec.release(); ex.release()
        val bytes=all.toByteArray()
        pcm=ShortArray(bytes.size/2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        require(pcm.size>=rate*2) { "Ses dosyası çok kısa veya çözümlenemedi." }
        durationUs=(pcm.size/2L)*1_000_000L/rate
        buildBassEnvelope()
    }

    private fun buildBassEnvelope() {
        val frames=pcm.size/2
        val filtered=FloatArray(frames)
        // Cascaded high-pass 35 Hz and low-pass 150 Hz one-pole filters.
        val hpA=exp(-2.0*PI*35/rate)
        val lpA=1.0-exp(-2.0*PI*150/rate)
        var prevX=0.0; var hp=0.0; var lp=0.0
        for(i in 0 until frames) {
            val x=((pcm[i*2].toInt()+pcm[i*2+1].toInt())/2.0)/32768.0
            hp=hpA*(hp+x-prevX); prevX=x
            lp += lpA*(hp-lp)
            filtered[i]=lp.toFloat()
        }
        val win=rate/100
        val rms=FloatArray((frames+win-1)/win)
        for(k in rms.indices) {
            var s=0.0; val end=min(frames,(k+1)*win)
            for(i in k*win until end) s+=filtered[i]*filtered[i]
            rms[k]=sqrt(s/max(1,end-k*win)).toFloat()
        }
        val peak=(rms.maxOrNull() ?: 0.001f).coerceAtLeast(0.0001f)
        envelope=FloatArray(rms.size)
        var sm=0f
        for(i in rms.indices) {
            val target=(rms[i]/peak).coerceIn(0f,1f)
            sm += (target-sm)*(if(target>sm) 0.22f else 0.065f)
            envelope[i]=sm
        }
    }

    fun makeSilentVideo(out:File) {
        decodeAudio()
        val recorder=MediaRecorder()
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(width,height)
        recorder.setVideoFrameRate(fps)
        recorder.setVideoEncodingBitRate(if(width>1280) 8_000_000 else 4_000_000)
        recorder.setOutputFile(out.absolutePath)
        recorder.prepare()
        val surface=recorder.surface
        recorder.start()
        val total=(durationUs*fps/1_000_000L).toInt().coerceAtLeast(1)
        val paint=Paint().apply { isAntiAlias=false }
        val start=System.nanoTime()
        for(frame in 0 until total) {
            val t=frame.toLong()*1_000_000L/fps
            val sample=(t*rate/1_000_000L).toInt()
            val envIndex=(sample/(rate/100)).coerceIn(0,envelope.lastIndex)
            val white=(envelope[envIndex]*255).roundToInt().coerceIn(0,255)
            val canvas:Canvas=surface.lockCanvas(null)
            try { canvas.drawColor(Color.rgb(white,white,white),android.graphics.PorterDuff.Mode.SRC) }
            finally { surface.unlockCanvasAndPost(canvas) }
            if(frame%max(1,total/100)==0) report(frame*55/total,"Görüntü hazırlanıyor: %${frame*55/total}")
            // Pace frames to 60 fps so MediaRecorder timestamps remain real-time.
            val target=start+(frame+1)*1_000_000_000L/fps
            while(System.nanoTime()<target) Thread.sleep(0,500_000)
        }
        recorder.stop(); recorder.reset(); recorder.release()
    }

    fun makeAacAudio(out:File) {
        val encoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val fmt=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,rate,ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE,192000)
        }
        encoder.configure(fmt,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); encoder.start()
        val mux=MediaMuxer(out.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val info=MediaCodec.BufferInfo()
        var track=-1; var started=false; var offset=0; var submittedFrames=0L; var inputDone=false; var done=false
        while(!done) {
            if(!inputDone) {
                val ix=encoder.dequeueInputBuffer(10000)
                if(ix>=0) {
                    val b=encoder.getInputBuffer(ix)!!; b.clear()
                    val remaining=pcm.size-offset
                    if(remaining<=0) {
                        encoder.queueInputBuffer(ix,0,0,durationUs,MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone=true
                    } else {
                        val shorts=min(b.remaining()/2, min(2048,remaining))
                        val bytes=shorts*2
                        for(i in 0 until shorts) b.putShort(pcm[offset+i])
                        val pts=submittedFrames*1_000_000L/rate
                        encoder.queueInputBuffer(ix,0,bytes,pts,0)
                        offset+=shorts; submittedFrames+=shorts/ch
                    }
                }
            }
            val oi=encoder.dequeueOutputBuffer(info,10000)
            if(oi==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track=mux.addTrack(encoder.outputFormat); mux.start(); started=true
            } else if(oi>=0) {
                val b=encoder.getOutputBuffer(oi)!!
                if(info.size>0 && started) { b.position(info.offset); b.limit(info.offset+info.size); mux.writeSampleData(track,b,info) }
                if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0) done=true
                encoder.releaseOutputBuffer(oi,false)
            }
        }
        if(started) mux.stop()
        mux.release(); encoder.stop(); encoder.release()
        report(75,"Ses AAC olarak kodlandı.")
    }

    fun mux(video:File,audio:File,out:File) {
        val ve=MediaExtractor(); ve.setDataSource(video.absolutePath)
        val ae=MediaExtractor(); ae.setDataSource(audio.absolutePath)
        val mux=MediaMuxer(out.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var vt=-1; var at=-1
        for(i in 0 until ve.trackCount) {
            val f=ve.getTrackFormat(i)
            if(f.getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true) { ve.selectTrack(i); vt=mux.addTrack(f); break }
        }
        for(i in 0 until ae.trackCount) {
            val f=ae.getTrackFormat(i)
            if(f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true) { ae.selectTrack(i); at=mux.addTrack(f); break }
        }
        require(vt>=0 && at>=0) { "Video veya AAC ses izi oluşturulamadı." }
        mux.start()
        val info=MediaCodec.BufferInfo()
        val buf=ByteBuffer.allocate(2*1024*1024)
        var vDone=false; var aDone=false
        while(!vDone || !aDone) {
            if(!vDone) {
                buf.clear(); val n=ve.readSampleData(buf,0)
                if(n<0) vDone=true else {
                    info.offset=0; info.size=n; info.flags=ve.sampleFlags; info.presentationTimeUs=ve.sampleTime
                    mux.writeSampleData(vt,buf,info); ve.advance()
                }
            }
            if(!aDone) {
                buf.clear(); val n=ae.readSampleData(buf,0)
                if(n<0) aDone=true else {
                    info.offset=0; info.size=n; info.flags=ae.sampleFlags; info.presentationTimeUs=ae.sampleTime
                    mux.writeSampleData(at,buf,info); ae.advance()
                }
            }
        }
        mux.stop(); mux.release(); ve.release(); ae.release()
        report(95,"Ses ve görüntü birleştiriliyor.")
    }
}
