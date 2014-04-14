/*
	BASS internet radio example
	Copyright (c) 2002-2012 Un4seen Developments Ltd.
*/

package com.example.netradio;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
//import android.os.Bundle;
//import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.un4seen.bass.BASS;
import android.R.layout;

public class NetRadio extends Activity {
	int req; // request number/counter
	int chan; // stream handle

	/*static final String[] urls={ // preset stream URLs
		"http://www.radioparadise.com/musiclinks/rp_128-9.m3u", "http://www.radioparadise.com/musiclinks/rp_32.m3u",
		"http://ogg2.as34763.net/vr160.ogg", "http://ogg2.as34763.net/vr32.ogg",
		"http://ogg2.as34763.net/a8160.ogg", "http://ogg2.as34763.net/a832.ogg",
		"http://somafm.com/secretagent.pls", "http://somafm.com/secretagent24.pls",
		"http://somafm.com/suburbsofgoa.pls", "http://somafm.com/suburbsofgoa24.pls"
	};*/

	Handler handler=new Handler();
	Runnable timer;
	Object lock = new Object();
	
	class RunnableParam implements Runnable {
		Object param;
		RunnableParam(Object p) { param=p; }
		public void run() {}
	}
	
	// display error messages
	void Error(String es) {
		// get error code in current thread for display in UI thread
		String s=String.format("%s\n(error code: %d)", es, BASS.BASS_ErrorGetCode());
		runOnUiThread(new RunnableParam(s) {
            public void run() {
        		new AlertDialog.Builder(NetRadio.this)
    				.setMessage((String)param)
    				.setPositiveButton("OK", null)
    				.show();
            }
		});
	}

	// update stream title from metadata
	void DoMeta() {
		String meta=(String)BASS.BASS_ChannelGetTags(chan, BASS.BASS_TAG_META);
		if (meta!=null) { // got Shoutcast metadata
			int ti=meta.indexOf("StreamTitle='");
			if (ti>=0) {
				String title=meta.substring(ti+13, meta.indexOf("'", ti+13));
				((TextView)findViewById(R.id.status1)).setText(title);
			}
		} else {
			String[] ogg=(String[])BASS.BASS_ChannelGetTags(chan, BASS.BASS_TAG_OGG);
			if (ogg!=null) { // got Icecast/OGG tags
				String artist=null, title=null;
				for (String s: ogg) {
					if (s.regionMatches(true, 0, "artist=", 0, 7))
					    artist=s.substring(7);
					else if (s.regionMatches(true, 0, "title=", 0, 6))
					    title=s.substring(6);
				}
				if (title!=null) {
					if (artist!=null)
						((TextView)findViewById(R.id.status1)).setText(title+" - "+title);
					else
						((TextView)findViewById(R.id.status1)).setText(title);
				}
			}
		}
	}

	BASS.SYNCPROC MetaSync=new BASS.SYNCPROC() {
    	public void SYNCPROC(int handle, int channel, int data, Object user) {
			runOnUiThread(new Runnable() {
                public void run() {
					DoMeta();
                }
            });
    	}
    };
    
	BASS.SYNCPROC EndSync=new BASS.SYNCPROC() {
    	public void SYNCPROC(int handle, int channel, int data, Object user) {
			runOnUiThread(new Runnable() {
                public void run() {
					((TextView)findViewById(R.id.status2)).setText("not playing");
					((TextView)findViewById(R.id.status1)).setText("");
					((TextView)findViewById(R.id.status3)).setText("");
				}
			});
    	}
    };

	BASS.DOWNLOADPROC StatusProc=new BASS.DOWNLOADPROC() {
    	public void DOWNLOADPROC(ByteBuffer buffer, int length, Object user) {
			if (buffer!=null && length==0 && (Integer)user==req) { // got HTTP/ICY tags, and this is still the current request
				String[] s;
				try {
					CharsetDecoder dec=Charset.forName("ISO-8859-1").newDecoder();
            		ByteBuffer temp=ByteBuffer.allocate(buffer.limit()); // CharsetDecoder doesn't like a direct buffer?
					temp.put(buffer);
					temp.position(0);
					s=dec.decode(temp).toString().split("\0"); // convert buffer to string array
				} catch (Exception e) {
					return;
				}
				runOnUiThread(new RunnableParam(s[0]) { // 1st string = status
					public void run() {
						((TextView)findViewById(R.id.status3)).setText((String)param);
					}
				});
			}
    	}
    };
    
	public class OpenURL implements Runnable {
		String url;
		public OpenURL(String p) { url=p; }
		public void run() {
			int r;
			synchronized(lock) { // make sure only 1 thread at a time can do the following
				r=++req; // increment the request counter for this request
			}
			BASS.BASS_StreamFree(chan); // close old stream
			runOnUiThread(new Runnable() {
                public void run() {
					((TextView)findViewById(R.id.status2)).setText("connecting...");
					((TextView)findViewById(R.id.status1)).setText("");
					((TextView)findViewById(R.id.status3)).setText("");
				}
			});
			BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_PLAYLIST, 1);
			int c=BASS.BASS_StreamCreateURL(url, 0, BASS.BASS_STREAM_BLOCK|BASS.BASS_STREAM_STATUS|BASS.BASS_STREAM_AUTOFREE, StatusProc, r); // open URL
			synchronized(lock) {
				if (r!=req) { // there is a newer request, discard this stream
					if (c!=0) BASS.BASS_StreamFree(c);
					return;
				}
				chan=c; // this is now the current stream
			}
			if (chan==0) { // failed to open
                runOnUiThread(new Runnable() {
                	public void run() {
					    ((TextView)findViewById(R.id.status2)).setText("not playing");
                	}
                });
        		Error("Can't play the stream");
			} else
	            handler.postDelayed(timer, 50); // start prebuffer monitoring
		}
	}
	
	public void Stop(View v)
	{
		((TextView)findViewById(R.id.status2)).setText("Stoped.");
		((TextView)findViewById(R.id.status1)).setText("");
		((TextView)findViewById(R.id.status3)).setText("");
		BASS.BASS_StreamFree(chan); // close old stream
	}
	
	public void Play(View v) {
		String proxy=null;
		if (!((CheckBox)findViewById(R.id.proxydirect)).isChecked())
			proxy=((EditText)findViewById(R.id.proxy)).getText().toString();
		BASS.BASS_SetConfigPtr(BASS.BASS_CONFIG_NET_PROXY, proxy); // set proxy server
		String url;
		if (v.getId()==R.id.opencustom)
			url=((EditText)findViewById(R.id.custom)).getText().toString();
		else
		{
			
			url=(String)v.getContentDescription();
		}
		new Thread(new OpenURL(url)).start();
	}
	
	private static final String TAG = "MainActivity";
	private MusicIntentReceiver myReceiver;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

		// initialize output device
		if (!BASS.BASS_Init(-1, 44100, 0)) {
			Error("Can't initialize device");
			return;
		}

        String path=getApplicationInfo().nativeLibraryDir;

		int pl=BASS.BASS_PluginLoad(path+"/"+"libbass_aac.so",0);
		if(pl==0)
		{
			Error("Can't upload plugin AAC");
		}
		BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_PLAYLIST, 1); // enable playlist processing
		BASS.BASS_SetConfig(BASS.BASS_CONFIG_NET_PREBUF, 0); // minimize automatic pre-buffering, so we can do it (and display it) instead

        timer=new Runnable() {
	        public void run() {
				// monitor prebuffering progress
				long progress=BASS.BASS_StreamGetFilePosition(chan, BASS.BASS_FILEPOS_BUFFER)
					*100/BASS.BASS_StreamGetFilePosition(chan, BASS.BASS_FILEPOS_END); // percentage of buffer filled
				if (progress>75 || BASS.BASS_StreamGetFilePosition(chan, BASS.BASS_FILEPOS_CONNECTED)==0) { // over 75% full (or end of download)
					// get the broadcast name and URL
					String[] icy=(String[])BASS.BASS_ChannelGetTags(chan, BASS.BASS_TAG_ICY);
					if (icy==null) icy=(String[])BASS.BASS_ChannelGetTags(chan, BASS.BASS_TAG_HTTP); // no ICY tags, try HTTP
					if (icy!=null) {
						for (String s: icy) {
							if (s.regionMatches(true, 0, "icy-name:", 0, 9))
					            ((TextView)findViewById(R.id.status2)).setText(s.substring(9));
							else if (s.regionMatches(true, 0, "icy-url:", 0, 8))
					            ((TextView)findViewById(R.id.status3)).setText(s.substring(8));
						}
					} else
			            ((TextView)findViewById(R.id.status2)).setText("");
					// get the stream title and set sync for subsequent titles
					DoMeta();
					BASS.BASS_ChannelSetSync(chan, BASS.BASS_SYNC_META, 0, MetaSync, 0); // Shoutcast
					BASS.BASS_ChannelSetSync(chan, BASS.BASS_SYNC_OGG_CHANGE, 0, MetaSync, 0); // Icecast/OGG
					// set sync for end of stream
					BASS.BASS_ChannelSetSync(chan, BASS.BASS_SYNC_END, 0, EndSync, 0);
					// play it!
					BASS.BASS_ChannelPlay(chan, false);
				} else {
		            ((TextView)findViewById(R.id.status2)).setText(String.format("buffering... %d%%", progress));
		            handler.postDelayed(this, 50);
				}
	        }
	    };
	    this.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
	    //setContentView(R.layout.activity_main);
	    myReceiver = new MusicIntentReceiver();
    }
    
    @Override
    public void onDestroy() {
    	BASS.BASS_Free();

    	super.onDestroy();
    }

@Override public void onResume() {
    IntentFilter filter = new IntentFilter(Intent.ACTION_HEADSET_PLUG);
    registerReceiver(myReceiver, filter);
    super.onResume();
}

private class MusicIntentReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_HEADSET_PLUG)) {
            int state = intent.getIntExtra("state", -1);
            switch (state) {
            case 0:
                Log.d(TAG, "Headset is unplugged");
                Stop(findViewById(R.id.button1));
                break;
            case 1:
                Log.d(TAG, "Headset is plugged");
                break;
            default:
                Log.d(TAG, "I have no idea what the headset state is");
            }
        }
    }
}

@Override public void onPause() {
    unregisterReceiver(myReceiver);
    super.onPause();
}
}