/**
 DR Radio 2 is developed by Jacob Nordfalk, Hanafi Mughrabi and Frederik Aagaard.
 Some parts of the code are loosely based on Sveriges Radio Play for Android.

 DR Radio 2 for Android is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License version 2 as published by
 the Free Software Foundation.

 DR Radio 2 for Android is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 DR Radio 2 for Android.  If not, see <http://www.gnu.org/licenses/>.

 */

package dk.dr.radio.afspilning;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Vibrator;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import com.android.deskclock.AlarmAlertWakeLock;
import com.android.volley.Request;
import com.android.volley.VolleyError;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import dk.dr.radio.data.DRData;
import dk.dr.radio.data.DRJson;
import dk.dr.radio.data.Kanal;
import dk.dr.radio.data.Lydkilde;
import dk.dr.radio.data.Lydstream;
import dk.dr.radio.diverse.AfspillerIkonOgNotifikation;
import dk.dr.radio.diverse.App;
import dk.dr.radio.diverse.Log;
import dk.dr.radio.diverse.MediabuttonReceiver;
import dk.dr.radio.diverse.Opkaldshaandtering;
import dk.dr.radio.diverse.volley.DrVolleyResonseListener;
import dk.dr.radio.diverse.volley.DrVolleyStringRequest;

/**
 * @author j
 */
public class Afspiller {

  private final GemiusStatistik gemiusStatistik;
  public Status afspillerstatus = Status.STOPPET;
  // Burde være en del af afspillerstatus
  private boolean afspilningPåPause;

  private MediaPlayerWrapper mediaPlayer;
  private MediaPlayerLytter lytter = new MediaPlayerLytterImpl();

  public List<Runnable> observatører = new ArrayList<Runnable>();
  public List<Runnable> forbindelseobservatører = new ArrayList<Runnable>();

  private Lydstream lydstream;
  private int forbinderProcent;
  private Lydkilde lydkilde;
  public boolean eraroSignifasBrui;
  public PowerManager.WakeLock wakeLock;

  private static void sætMediaPlayerLytter(MediaPlayerWrapper mediaPlayer, MediaPlayerLytter lytter) {
    mediaPlayer.setMediaPlayerLytter(lytter);
    if (lytter != null && App.prefs.getBoolean(NØGLEholdSkærmTændt, false)) {
      mediaPlayer.setWakeMode(App.instans, PowerManager.SCREEN_DIM_WAKE_LOCK);
    }
  }

  static final String NØGLEholdSkærmTændt = "holdSkærmTændt";
  private WifiLock wifilock = null;

  /**
   * Forudsætter DRData er initialiseret
   */
  public Afspiller() {
    mediaPlayer = MediaPlayerWrapper.opret();

    sætMediaPlayerLytter(mediaPlayer, this.lytter);
    // Indlæs gamle værdier så vi har nogle...
    // Fjernet. Skulle ikke være nødvendigt. Jacob 22/10-2011
    // kanalNavn = p.getString("kanalNavn", "P1");
    // lydUrl = p.getString("lydUrl", "rtsp://live-rtsp.dr.dk/rtplive/_definst_/Channel5_LQ.stream");

    // Gem værdi hvis den ikke findes, sådan at indstillingsskærm viser det rigtige
    if (!App.prefs.contains(NØGLEholdSkærmTændt)) {
      // Xperia Play har brug for at holde skærmen tændt. Muligvis også andre....
      boolean holdSkærmTændt = "R800i".equals(Build.MODEL);
      App.prefs.edit().putBoolean(NØGLEholdSkærmTændt, holdSkærmTændt).commit();
    }

    wifilock = ((WifiManager) App.instans.getSystemService(Context.WIFI_SERVICE)).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DR Radio");
    wifilock.setReferenceCounted(false);
    Opkaldshaandtering opkaldshåndtering = new Opkaldshaandtering(this);
    TelephonyManager tm = (TelephonyManager) App.instans.getSystemService(Context.TELEPHONY_SERVICE);
    tm.listen(opkaldshåndtering, PhoneStateListener.LISTEN_CALL_STATE);
    /*
    // Opret en baggrundstråd med en Handler til at sende Runnables ind i
    new Thread() {
      public void run() {
        Looper.prepare();
        baggrundstråd = new Handler();
        Looper.loop();
      }
    }.start();
    */
    if (App.fejlsøgning) tjekLydAktiv.run();
    gemiusStatistik = new GemiusStatistik();
  }

  private int onErrorTæller;
  private long onErrorTællerNultid;

  public void startAfspilning() {
    eraroSignifasBrui = false;
    if (lydkilde.hentetStream == null && !App.erOnline()) {
      App.kortToast("Internetforbindelse mangler");
      return;
    }
    if (lydkilde.hentetStream == null && lydkilde.streams == null) {
      Request<?> req = new DrVolleyStringRequest(lydkilde.getStreamsUrl(), new DrVolleyResonseListener() {
        @Override
        public void fikSvar(String json, boolean fraCache, boolean uændret) throws Exception {
          if (uændret) return; // ingen grund til at parse det igen
          JSONObject o = new JSONObject(json);
          lydkilde.streams = DRJson.parsStreams(o.getJSONArray(DRJson.Streams.name()));
          Log.d("Afspiller hentStreams " + lydkilde + " fraCache=" + fraCache + " k.lydUrl=" + lydkilde.streams);
          if (onErrorTæller++ > 10)
            Log.rapporterFejl(new Exception("onErrorTæller++>10, uendelig løkke afværget"), lydkilde);
          else startAfspilning(); // Opdatér igen
        }

        @Override
        protected void fikFejl(VolleyError error) {
          App.kortToast("Internetforbindelse mangler");
          if (eraroSignifasBrui) ringDenAlarm();
          super.fikFejl(error);
        }
      }) {
        public Priority getPriority() {
          return Priority.IMMEDIATE;
        }
      };
      App.volleyRequestQueue.add(req);
      return;
    }
    Log.d("startAfspilning() " + lydkilde);

    onErrorTæller = 0;
    onErrorTællerNultid = System.currentTimeMillis();

    if (afspillerstatus == Status.STOPPET) {
      //opdaterNotification();
      // Start afspillerservicen så programmet ikke bliver lukket
      // når det kører i baggrunden under afspilning
      App.instans.startService(new Intent(App.instans, HoldAppIHukommelsenService.class));
      if (App.prefs.getBoolean("wifilås", true) && wifilock != null) {
        wifilock.acquire();
      }

      AudioManager audioManager = (AudioManager) App.instans.getSystemService(Context.AUDIO_SERVICE);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.FROYO) {
        // Se http://developer.android.com/training/managing-audio/audio-focus.html
        int result = audioManager.requestAudioFocus(getOnAudioFocusChangeListener(),
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN);
        Log.d("requestAudioFocus res=" + result);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
          MediabuttonReceiver.registrér();
        }
      }
      startAfspilningIntern();


      // Skru op til 1/5 styrke hvis volumen er lavere end det
      tjekVolumenMindst5tedele(1);

    } else Log.d(" forkert status=" + afspillerstatus);
  }

  /** Sørg for at volumen er skruet op til en minimumsværdi, angivet i 5'tedele af fuld styrke */
  public void tjekVolumenMindst5tedele(int min5) {
    AudioManager audioManager = (AudioManager) App.instans.getSystemService(Context.AUDIO_SERVICE);
    int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
    int nu = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    if (nu < min5 * max / 5) {
      audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, min5 * max / 5, AudioManager.FLAG_SHOW_UI);
    }
  }


  /**
   * Typen er OnAudioFocusChangeListener, men da den ikke findes i API<8 kan vi ikke bruge klassen her
   */
  Object onAudioFocusChangeListener;

  /**
   * Responding to the loss of audio focus
   */
  @SuppressLint("NewApi")
  private OnAudioFocusChangeListener getOnAudioFocusChangeListener() {
    if (onAudioFocusChangeListener == null)
      onAudioFocusChangeListener = new OnAudioFocusChangeListener() {

        private int lydstyreFørDuck = -1;

        @TargetApi(Build.VERSION_CODES.FROYO)
        public void onAudioFocusChange(int focusChange) {
          Log.d("onAudioFocusChange " + focusChange);
          AudioManager am = (AudioManager) App.instans.getSystemService(Context.AUDIO_SERVICE);

          switch (focusChange) {
            // Kommer ved f.eks. en SMS eller taleinstruktion i Google Maps
            case (AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK):
              Log.d("JPER duck");
              // Vi 'dukker' lyden mens den vigtigere lyd høres
              // Sæt lydstyrken ned til en 1/3-del
              lydstyreFørDuck = am.getStreamVolume(AudioManager.STREAM_MUSIC);
              am.setStreamVolume(AudioManager.STREAM_MUSIC, (lydstyreFørDuck + 2) / 3, 0);
              break;

            // Dette sker ved f.eks. opkald
            case (AudioManager.AUDIOFOCUS_LOSS_TRANSIENT):
              Log.d("JPER pause");
              if (afspillerstatus != Status.STOPPET) {
                pauseAfspilning(); // sætter afspilningPåPause=false
                afspilningPåPause = true;
              }
              break;

            // Dette sker hvis en anden app med lyd startes, f.eks. et spil
            case (AudioManager.AUDIOFOCUS_LOSS):
              Log.d("JPER stop");
              stopAfspilning();
              am.abandonAudioFocus(this);
              break;

            // Dette sker når opkaldet er slut og ved f.eks. opkald
            case (AudioManager.AUDIOFOCUS_GAIN):
              Log.d("JPER Gain");
              if (afspillerstatus == Status.STOPPET) {
                if (afspilningPåPause) startAfspilningIntern();
              } else {
                // Genskab lydstyrke før den blev dukket
                if (lydstyreFørDuck > 0) {
                  am.setStreamVolume(AudioManager.STREAM_MUSIC, lydstyreFørDuck, 0);
                }
                // Genstart ikke afspilning, der spilles allerede!
                //startAfspilningIntern();
              }
          }
        }
      };
    return (OnAudioFocusChangeListener) onAudioFocusChangeListener;
  }

  long setDataSourceTid = 0;
  boolean setDataSourceLyd = false;

  private String mpTils() {
    AudioManager ar = (AudioManager) App.instans.getSystemService(App.AUDIO_SERVICE);
    //return mediaPlayer.getCurrentPosition()+ "/"+mediaPlayer.getDuration() + "    "+mediaPlayer.isPlaying()+ar.isMusicActive();
    if (!setDataSourceLyd && ar.isMusicActive()) {
      setDataSourceLyd = true;
      String str = "Det tog " + (System.currentTimeMillis() - setDataSourceTid) / 100 / 10.0 + " sek før lyden kom";
      Log.d(str);
      if (App.fejlsøgning) {
        App.langToast(str);
      }
    }
    return "    " + ar.isMusicActive() + " dt=" + (System.currentTimeMillis() - setDataSourceTid) + "ms";
  }

  synchronized private void startAfspilningIntern() {
    MediabuttonReceiver.registrér();
    MediabuttonReceiver.opdaterBillede(this);
    afspillerstatus = Status.FORBINDER;
    afspilningPåPause = false;
    sendOnAfspilningForbinder(-1);
    opdaterObservatører();
    handler.removeCallbacks(startAfspilningIntern);

    // mediaPlayer.setDataSource() bør kaldes fra en baggrundstråd da det kan ske
    // at den hænger under visse netværksforhold
    new Thread() {
      public void run() {
        setDataSourceTid = System.currentTimeMillis();
        setDataSourceLyd = false;
        try {
          lydstream = lydkilde.findBedsteStreams(false).get(0);

          if (lydstream == null) {
            Log.rapporterFejl(new IllegalStateException("Ingen lydUrl for " + lydkilde + ": " + lydkilde.streams));
            App.kortToast("Kunne ikke oprette forbindelse");
            return;
          }
          gemiusStatistik.setLydkilde(lydkilde);
          DRData.instans.senestLyttede.registrérLytning(lydkilde);
          Log.d("mediaPlayer.setDataSource( " + lydstream);

          mediaPlayer.setDataSource(lydstream.url);
          Log.d("mediaPlayer.setDataSource() slut");
          mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
          Log.d("mediaPlayer.setDataSource() slut  " + mpTils());
          mediaPlayer.prepare();
          Log.d("mediaPlayer.prepare() slut  " + mpTils());
        } catch (Exception ex) {
          if (!App.PRODUKTION) Log.rapporterFejl(ex);
          else Log.e("Fejl for lyd-stream " + lydstream, ex);
          //ex = new Exception("spiller "+kanalNavn+" "+lydUrl, ex);
          //Log.kritiskFejlStille(ex);
          handler.post(new Runnable() {
            public void run() { // Stop afspilleren fra forgrundstråden. Jacob 14/11
              lytter.onError(null, 42, 42); // kalder stopAfspilning(); og forsøger igen senere og melder fejl til bruger efter 10 forsøg
            }
          });
        }
      }
    }.start();
  }

  synchronized private void pauseAfspilningIntern() {
    handler.removeCallbacks(startAfspilningIntern);
    // Da mediaPlayer.reset() erfaringsmæssigt kan hænge i dette tilfælde afregistrerer vi
    // alle lyttere og bruger en ny
    final MediaPlayerWrapper gammelMediaPlayer = mediaPlayer;
    sætMediaPlayerLytter(gammelMediaPlayer, null); // afregistrér alle lyttere
    new Thread() {
      @Override
      public void run() {
        try {
          try { // Ignorér IllegalStateException, det er fordi den allerede er stoppet
            gammelMediaPlayer.stop();
          } catch (IllegalStateException e) {
          }
          Log.d("gammelMediaPlayer.release() start");
          gammelMediaPlayer.release();
          Log.d("gammelMediaPlayer.release() færdig");
        } catch (Exception e) {
          Log.rapporterFejl(e);
        }
      }
    }.start();

    mediaPlayer = MediaPlayerWrapper.opret();
    sætMediaPlayerLytter(mediaPlayer, this.lytter); // registrér lyttere på den nye instans

    afspillerstatus = Status.STOPPET;
    afspilningPåPause = false;
    opdaterObservatører();
  }

  synchronized public void pauseAfspilning() {
    int pos = gemPosition();
    pauseAfspilningIntern();
    if (wifilock != null) wifilock.release();
    gemiusStatistik.registérHændelse(GemiusStatistik.PlayerAction.Pause, pos / 1000);
  }

  /**
   * Gem position - og spol herhen næste gang udsendelsen spiller
   */
  private int gemPosition() {
    if (!lydkilde.erDirekte() && afspillerstatus == Status.SPILLER)
      {
        int pos = mediaPlayer.getCurrentPosition();
        if (pos > 0) {
          //senestLyttet.getUdsendelse().startposition = pos;
          DRData.instans.senestLyttede.sætStartposition(lydkilde, pos);
        }
        return pos;
      }
    return 0;
  }


  synchronized public void stopAfspilning() {
    Log.d("Afspiller stopAfspilning");
    gemiusStatistik.registérHændelse(GemiusStatistik.PlayerAction.Stopped, getCurrentPosition() / 1000);
    pauseAfspilning();
    // Stop afspillerservicen
    App.instans.stopService(new Intent(App.instans, HoldAppIHukommelsenService.class));
    if (wakeLock != null) {
      wakeLock.release();
      wakeLock = null;
    }
  }


  public void setLydkilde(Lydkilde lydkilde) {
    if (lydkilde == this.lydkilde) return;
    if (lydkilde == null) {
      Log.rapporterFejl(new IllegalStateException("setLydkilde(null"));
      return;
    }
    if (lydkilde instanceof Kanal && Kanal.P4kode.equals(((Kanal) lydkilde).kode)) { // TODO - fjern tjek
      // Nærmere fix for https://www.bugsense.com/dashboard/project/cd78aa05/errors/820758400
      // Log.rapporterFejl(new IllegalStateException("setLydkilde(P4F"));
      // return;

      // Nyt fix - vi vælger bare en underkanal.
      String kanalkode = App.tjekP4OgVælgUnderkanal(((Kanal) lydkilde).kode);
      lydkilde = DRData.instans.grunddata.kanalFraKode.get(kanalkode);
    }
    Log.d("setLydkilde(" + lydkilde);


    if ((afspillerstatus == Status.SPILLER) || (afspillerstatus == Status.FORBINDER)) {
      stopAfspilning(); // gemmer lydkildens position
      this.lydkilde = lydkilde;
      try {
        startAfspilning(); // sætter afspilleren til den nye lydkildes position
      } catch (Exception e) {
        Log.rapporterFejl(e); // TODO fjern efter et par måneder i drift
      }
    } else {
      this.lydkilde = lydkilde;
    }
    opdaterObservatører();
  }


  private void opdaterObservatører() {

    AppWidgetManager mAppWidgetManager = AppWidgetManager.getInstance(App.instans);
    int[] appWidgetId = mAppWidgetManager.getAppWidgetIds(new ComponentName(App.instans, AfspillerIkonOgNotifikation.class));

    for (int id : appWidgetId) {
      AfspillerIkonOgNotifikation.opdaterUdseende(App.instans, mAppWidgetManager, id);
    }

    // Notificér alle i observatørlisen - fra en kopi, sådan at de kan fjerne
    // sig selv fra listen uden at det giver ConcurrentModificationException
    for (Runnable observatør : new ArrayList<Runnable>(observatører)) {
      observatør.run();
    }
  }


  public Status getAfspillerstatus() {
    return afspillerstatus;
  }


  public int getForbinderProcent() {
    return forbinderProcent;
  }

  public Lydkilde getLydkilde() {
    return lydkilde;
  }


  Handler handler = new Handler();
  Runnable startAfspilningIntern = new Runnable() {
    public void run() {
      startAfspilningIntern();
    }
  };

  Runnable venterPåAtKommeOnline = new Runnable() {
    @Override
    public void run() {
      App.netværk.observatører.remove(venterPåAtKommeOnline);
      //if (afspillerstatus==Status.STOPPET) return; // Spiller ikke
      if (lydkilde.hentetStream != null) return; // Offline afspilning - ignorér
      try {
        if (!App.erOnline())
          Log.e(new IllegalStateException("Burde være online her??!"));
        long dt = System.currentTimeMillis() - onErrorTællerNultid;
        Log.d("Vi kom online igen efter " + dt + " ms");
        if (dt < 5 * 60 * 1000) {
          Log.d("Genstart afspilning");
          startAfspilningIntern(); // Genstart
        } else {
          Log.d("Brugeren har nok glemt os, afslut");
          stopAfspilning();
        }
      } catch (Exception e) {
        Log.rapporterFejl(e);
      }
    }
  };

  private void sendOnAfspilningForbinder(int procent) {
    forbinderProcent = procent;
    for (Runnable runnable : forbindelseobservatører) {
      runnable.run();
    }
  }

  /** Flyt til position (i millisekunder) */
  public void seekTo(int offsetMs) {
    mediaPlayer.seekTo(offsetMs);
    gemiusStatistik.registérHændelse(GemiusStatistik.PlayerAction.Seeking, offsetMs / 1000);
  }

  /** Længde i millisekunder */
  public int getDuration() {
    if (afspillerstatus == Status.SPILLER) return mediaPlayer.getDuration();
    return 0;
  }

  /** Position i millisekunder */
  public int getCurrentPosition() {
    if (afspillerstatus == Status.SPILLER) return mediaPlayer.getCurrentPosition();
    return 0;
  }

  //
  //    TILBAGEKALD FRA MEDIAPLAYER
  //
  class MediaPlayerLytterImpl implements MediaPlayerLytter {
    public void onPrepared(MediaPlayer mp) {
      Log.d("onPrepared " + mpTils());
      afspillerstatus = Status.SPILLER; //No longer buffering
      opdaterObservatører();
      // Det ser ud til kaldet til start() kan tage lang tid på Android 4.1 Jelly Bean
      // (i hvert fald på Samsung Galaxy S III), så vi kalder det i baggrunden
      new Thread() {
        public void run() {
          try { // Fix for https://www.bugsense.com/dashboard/project/cd78aa05/errors/825188032
            Log.d("mediaPlayer.start() " + mpTils());
            int startposition = DRData.instans.senestLyttede.getStartposition(lydkilde);
            Log.d("mediaPlayer genoptager afspilning ved " + startposition);
            gemiusStatistik.registérHændelse(GemiusStatistik.PlayerAction.Play, startposition / 1000);
            if (startposition > 0) {
              mediaPlayer.seekTo(startposition);
            }
            mediaPlayer.start();
            Log.d("mediaPlayer.start() slut " + mpTils());
            Thread.sleep(5000); // Vent lidt før data sendes
            if (App.netværk.erOnline()) {
              gemiusStatistik.startSendData();
            } // Ellers venter vi, det kan være vi er heldige at brugeren er online ved næste hændelse
          } catch (Exception e) {
            Log.rapporterFejl(e);
          }
        }
      }.start();
    }

    public void onCompletion(MediaPlayer mp) {
      Log.d("AfspillerService onCompletion!");
      // Hvis forbindelsen mistes kommer der en onCompletion() og vi er derfor
      // nødt til at genstarte, medmindre brugeren trykkede stop
      if (afspillerstatus == Status.SPILLER) {
        mediaPlayer.stop();
        // mediaPlayer.reset();
        // Da mediaPlayer.reset() erfaringsmæssigt kan hænge i dette tilfælde afregistrerer vi
        // alle lyttere og bruger en ny
        final MediaPlayerWrapper gammelMediaPlayer = mediaPlayer;
        sætMediaPlayerLytter(gammelMediaPlayer, null); // afregistrér alle lyttere
        new Thread() {
          public void run() {
            Log.d("gammelMediaPlayer.release() start");
            gammelMediaPlayer.release();
            Log.d("gammelMediaPlayer.release() færdig");
          }
        }.start();

        if (lydkilde.erDirekte()) {
          Log.d("Genstarter afspilning!");
          mediaPlayer = MediaPlayerWrapper.opret();
          sætMediaPlayerLytter(mediaPlayer, this); // registrér lyttere på den nye instans
          startAfspilningIntern();
        } else {
          DRData.instans.senestLyttede.sætStartposition(lydkilde, 0);
          gemiusStatistik.registérHændelse(GemiusStatistik.PlayerAction.Completed, getCurrentPosition() / 1000);
          stopAfspilning();
        }
      }
    }

    public boolean onError(MediaPlayer mp_UBRUGT, int hvad, int extra) {
      //Log.d("onError(" + MedieafspillerInfo.fejlkodeTilStreng(hvad) + "(" + hvad + ") " + extra+ " onErrorTæller="+onErrorTæller);
      Log.d("onError(" + hvad + ") " + extra + " onErrorTæller=" + onErrorTæller);


      if (Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN && hvad == MediaPlayer.MEDIA_ERROR_UNKNOWN
          && "GT-I9300".equals(Build.MODEL) && mediaPlayer.mediaPlayer.isPlaying()) {
        // Ignorer, da Samsung Galaxy SIII på Android 4.1 Jelly Bean
        // sender denne fejl (onError(1) -110) men i øvrigt spiller fint videre!
        return true;
      }

      // Iflg http://developer.android.com/guide/topics/media/index.html :
      // "It's important to remember that when an error occurs, the MediaPlayer moves to the Error
      //  state and you must reset it before you can use it again."
      if (afspillerstatus == Status.SPILLER || afspillerstatus == Status.FORBINDER) {


        // Hvis der har været
        // 1) færre end 10 fejl eller
        // 2) der højest er 1 fejl pr 20 sekunder så prøv igen
        long dt = System.currentTimeMillis() - onErrorTællerNultid;

        if (onErrorTæller++ < (App.fejlsøgning ? 2 : 10) || (dt / onErrorTæller > 20000)) {
          pauseAfspilningIntern();
          //mediaPlayer.stop();
          //mediaPlayer.reset();

          if (App.erOnline()) {
            // Vi venter længere og længere tid her
            int n = onErrorTæller;
            if (n > 11) n = 11;
            int ventetid = 10 + 5 * (1 << n); // fra n=0:10 msek til n=10:5 sek   til max n=11:10 sek
            Log.d("Ventetid før vi prøver igen: " + ventetid + "  n=" + n + " " + onErrorTæller);
            handler.postDelayed(startAfspilningIntern, ventetid);

            if (eraroSignifasBrui) {
              vibru(1000);
            }

          } else {
            Log.d("Vent på at vi kommer online igen");
            onErrorTællerNultid = System.currentTimeMillis();
            App.netværk.observatører.add(venterPåAtKommeOnline);
            if (eraroSignifasBrui) {
              ringDenAlarm();
            }
          }
        } else {
          pauseAfspilning(); // Vi giver op efter 10. forsøg
          App.langToast("Beklager, kan ikke spille radio");
          App.langToast("Prøv at vælge et andet format i indstillingerne");
        }
      } else {
        mediaPlayer.reset();
      }
      return true;
    }

    public void onBufferingUpdate(MediaPlayer mp, int procent) {
      if (App.fejlsøgning) Log.d("Afspiller onBufferingUpdate : " + procent + " " + mpTils());
      Log.d("Afspiller onBufferingUpdate : " + procent);
      if (procent < -100) procent = -1; // Ignorér vilde tal

      sendOnAfspilningForbinder(procent);
    }

    public void onSeekComplete(MediaPlayer mp) {
      Log.d("AfspillerService onSeekComplete");
      //opdaterObservatører();
    }
  }

  Runnable tjekLydAktiv = new Runnable() {
    @Override
    public void run() {
      App.forgrundstråd.removeCallbacks(this);
      AudioManager ar = (AudioManager) App.instans.getSystemService(App.AUDIO_SERVICE);
      Log.d("tjekLydAktiv " + ar.isMusicActive() + " " + mediaPlayer.mediaPlayer.isPlaying() + " " + getCurrentPosition() + " " + getDuration() + " " + new Date());
      App.forgrundstråd.postDelayed(this, 10000);
    }
  };

  void ringDenAlarm() {
    Uri alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
    if (alert == null) {
      // alert is null, using backup
      alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
      if (alert == null) {  // I can't see this ever being null (as always have a default notification) but just incase
        // alert backup is null, using 2nd backup
        alert = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
      }
    }
    lydkilde = new AlarmLydkilde(alert.toString(), lydkilde);
    handler.postDelayed(startAfspilningIntern, 100);
    vibru(4000);
  }

  private void vibru(int ms) {
    Log.d("vibru " + ms);
    try {
      Vibrator vibrator = (Vibrator) App.instans.getSystemService(Activity.VIBRATOR_SERVICE);
      vibrator.vibrate(ms);
      // Tenu telefonon veka por 1/2a sekundo
      AlarmAlertWakeLock.createPartialWakeLock(App.instans).acquire(500);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

}
