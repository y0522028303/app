package com.nova.mp3;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.EditText;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ = 77;
    private final String[] APPS = {"סייר קבצים", "הגדרות", "מוזיקה", "בלוטוס", "רשמקול"};
    private final String[] AUDIO_EXT = {".mp3",".m4a",".aac",".wav",".ogg",".3gp",".flac",".amr"};
    private final ArrayList<File> currentFiles = new ArrayList<>();
    private final ArrayList<Track> allTracks = new ArrayList<>();
    private final ArrayList<Track> visibleTracks = new ArrayList<>();
    private final ArrayList<String> groups = new ArrayList<>();
    private final ArrayList<String> groupTracks = new ArrayList<>();
    private final ArrayList<BluetoothDevice> btDevices = new ArrayList<>();
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private Handler handler = new Handler(Looper.getMainLooper());

    private Screen ui;
    private MediaPlayer player;
    private MediaRecorder recorder;
    private long pressStarted;
    private Runnable seekRunnable;
    private int appIndex = 0;
    private int page = 0;
    private int selected = 0;
    private File currentDir;
    private File selectedFile;
    private File internalRoot;
    private File externalRoot;
    private String breadcrumb = "";
    private int musicMode = 0;
    private String groupName = "";
    private int repeatMode = 0;
    private float speed = 1.0f;
    private boolean screenSaver = true;
    private int brightness = 100;
    private boolean recording;
    private File lastRecording;
    private int txtScroll = 0;
    private String txtContent = "";
    private BluetoothAdapter bluetooth;
    private BroadcastReceiver btReceiver;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        internalRoot = android.os.Environment.getExternalStorageDirectory();
        externalRoot = findExternalRoot();
        currentDir = internalRoot;

        ui = new Screen(this);
        setContentView(ui);

        bluetooth = BluetoothAdapter.getDefaultAdapter();
        btReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (BluetoothDevice.ACTION_FOUND.equals(i.getAction())) {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null && !containsDevice(d)) btDevices.add(d);
                    ui.invalidate();
                }
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(i.getAction())) ui.invalidate();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(btReceiver, f);

        if (!permissionsOk()) requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION
        }, REQ);

        scanAllMusic();
    }

    private boolean permissionsOk() {
        return Build.VERSION.SDK_INT < 23 ||
                (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                 checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r == REQ) { scanAllMusic(); ui.invalidate(); }
    }

    private File findExternalRoot() {
        try {
            File[] dirs = getExternalFilesDirs(null);
            for (int i = 1; i < dirs.length; i++) {
                if (dirs[i] == null) continue;
                String path = dirs[i].getAbsolutePath();
                int idx = path.indexOf("/Android/");
                if (idx > 0) {
                    File root = new File(path.substring(0, idx));
                    if (!root.equals(internalRoot) && root.exists()) return root;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void scanAllMusic() {
        scanExecutor.execute(() -> {
            ArrayList<Track> found = new ArrayList<>();
            scanMusicDir(internalRoot, false, found, 0);
            if (externalRoot != null && externalRoot.exists()) scanMusicDir(externalRoot, true, found, 0);
            found.sort(Comparator.comparing(t -> t.title.toLowerCase(Locale.ROOT)));
            runOnUiThread(() -> {
                allTracks.clear();
                allTracks.addAll(found);
                refreshMusicView();
            });
        });
    }

    private void scanMusicDir(File dir, boolean ext, ArrayList<Track> out, int depth) {
        if (dir == null || !dir.exists() || depth > 10 || out.size() > 2500) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                String n = f.getName();
                if (!n.equals("Android") && !n.startsWith(".")) scanMusicDir(f, ext, out, depth + 1);
            } else if (isAudio(f)) {
                Track t = readMeta(f, ext);
                if (t != null) out.add(t);
            }
        }
    }

    private boolean isAudio(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        for (String e : AUDIO_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    private boolean isTxt(File f) {
        return f.getName().toLowerCase(Locale.ROOT).endsWith(".txt");
    }

    private Track readMeta(File f, boolean ext) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        String artist = null, album = null, title = null;
        long duration = 0;
        try {
            r.setDataSource(f.getAbsolutePath());
            title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) duration = Long.parseLong(d);
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        return new Track(f.getAbsolutePath(), title, artist, album, ext, duration, Uri.fromFile(f));
    }

    private void refreshMusicView() {
        visibleTracks.clear();
        groups.clear();
        groupTracks.clear();

        if (musicMode == 0) {
            visibleTracks.addAll(allTracks);
        } else if (musicMode == 1) {
            for (Track t : allTracks) if (t.external) visibleTracks.add(t);
        } else if (musicMode == 2) {
            TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Track t : allTracks) set.add(t.artist);
            groups.addAll(set);
            if (!groupName.isEmpty()) for (Track t : allTracks) if (t.artist.equals(groupName)) visibleTracks.add(t);
        } else if (musicMode == 3) {
            TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Track t : allTracks) set.add(t.album);
            groups.addAll(set);
            if (!groupName.isEmpty()) for (Track t : allTracks) if (t.album.equals(groupName)) visibleTracks.add(t);
        }
        selected = Math.max(0, Math.min(selected, Math.max(0, displayCount() - 1)));
        ui.invalidate();
    }

    private int displayCount() {
        if (appIndex == 0 && page == 0) return 2;
        if (appIndex == 0 && page == 1) return currentFiles.size();
        if (appIndex == 1 && page == 0) return 3;
        if (appIndex == 2 && page == 0) return 4;
        if (appIndex == 2 && page == 1) return groups.size();
        if (appIndex == 2 && page == 2) return visibleTracks.size();
        if (appIndex == 3 && page == 0) return 2 + btDevices.size();
        if (appIndex == 4 && page == 0) return 2;
        return visibleTracks.size();
    }

    private void setApp(int i) {
        stopSeeking();
        page = 0; selected = 0; txtScroll = 0; groupName = "";
        appIndex = (i + APPS.length) % APPS.length;
        if (appIndex == 0) currentDir = internalRoot;
        if (appIndex == 2) { musicMode = 0; refreshMusicView(); }
        if (appIndex == 3) refreshBluetooth();
        refreshFiles();
        ui.invalidate();
    }

    private void refreshFiles() {
        currentFiles.clear();
        if (currentDir == null) return;
        File[] fs = currentDir.listFiles();
        if (fs == null) return;
        ArrayList<File> dirs = new ArrayList<>(), files = new ArrayList<>();
        for (File f : fs) {
            if (f.isDirectory()) dirs.add(f);
            else if (isAudio(f) || isTxt(f)) files.add(f);
        }
        Comparator<File> cmp = Comparator.comparing(x -> x.getName().toLowerCase(Locale.ROOT));
        dirs.sort(cmp); files.sort(cmp);
        currentFiles.addAll(dirs); currentFiles.addAll(files);
        selected = Math.max(0, Math.min(selected, Math.max(0, currentFiles.size()-1)));
    }

    private String pathLabel(File f) {
        if (f == null) return "";
        if (f.equals(internalRoot)) return "זיכרון פנימי";
        if (externalRoot != null && f.equals(externalRoot)) return "כרטיס זיכרון";
        String p = f.getAbsolutePath();
        String root = currentDir != null && isUnder(f, internalRoot) ? internalRoot.getAbsolutePath() :
                externalRoot != null && isUnder(f, externalRoot) ? externalRoot.getAbsolutePath() : "";
        if (!root.isEmpty() && p.startsWith(root)) {
            String rel = p.substring(root.length());
            if (rel.startsWith(File.separator)) rel = rel.substring(1);
            return rel.replace(File.separatorChar, '/');
        }
        return f.getName();
    }

    private boolean isUnder(File f, File root) {
        return root != null && f != null && (f.equals(root) || f.getAbsolutePath().startsWith(root.getAbsolutePath()+File.separator));
    }

    private void openSelected() {
        if (appIndex == 0) openFileItem();
        else if (appIndex == 1) openSetting();
        else if (appIndex == 2) openMusicItem();
        else if (appIndex == 3) openBluetoothItem();
        else if (appIndex == 4) openRecorderItem();
        ui.invalidate();
    }

    private void openFileItem() {
        if (page == 0) {
            currentDir = selected == 0 ? internalRoot : externalRoot;
            if (currentDir != null) { page = 1; selected = 0; refreshFiles(); }
            return;
        }
        if (currentFiles.isEmpty()) return;
        File f = currentFiles.get(selected);
        if (f.isDirectory()) {
            currentDir = f; selected = 0; refreshFiles(); breadcrumb = f.getName();
        } else if (isTxt(f)) {
            openTxt(f);
        } else if (isAudio(f)) {
            playPath(f);
        }
    }

    private void openTxt(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n=in.read(b))>0 && out.size()<120000) out.write(b,0,n);
            in.close();
            txtContent = new String(out.toByteArray(), "UTF-8");
            selectedFile = f; txtScroll = 0; page = 2;
        } catch (Exception e) { toast("לא ניתן לקרוא את הקובץ"); }
    }

    private void openSetting() {
        if (page == 0) {
            if (selected == 0) brightness = brightness == 100 ? 50 : brightness == 50 ? 25 : 100;
            else if (selected == 1) screenSaver = !screenSaver;
            else showMemoryInfo();
            ui.invalidate();
        }
    }

    private void showMemoryInfo() {
        long free = internalRoot == null ? 0 : internalRoot.getFreeSpace();
        long total = internalRoot == null ? 0 : internalRoot.getTotalSpace();
        new AlertDialog.Builder(this).setTitle("פרטי זיכרון")
            .setMessage("זיכרון פנימי\nפנוי: " + fmtBytes(free) + "\nנפח: " + fmtBytes(total) +
                    (externalRoot == null ? "" : "\n\nכרטיס זיכרון\nפנוי: " + fmtBytes(externalRoot.getFreeSpace()) +
                            "\nנפח: " + fmtBytes(externalRoot.getTotalSpace())))
            .setPositiveButton("אישור", null).show();
    }

    private void openMusicItem() {
        if (page == 0) {
            musicMode = selected;
            selected = 0; groupName = "";
            if (musicMode >= 2) page = 1; else page = 2;
            refreshMusicView();
        } else if (page == 1 && (musicMode == 2 || musicMode == 3)) {
            if (groups.isEmpty()) return;
            groupName = groups.get(selected);
            selected = 0; page = 2; refreshMusicView();
        } else if (page == 2) {
            if (visibleTracks.isEmpty()) return;
            playTrack(visibleTracks.get(selected));
        }
    }

    private void playTrack(Track t) {
        selectedFile = new File(t.path);
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(t.path);
            player.setOnPreparedListener(m -> {
                try { m.setPlaybackParams(m.getPlaybackParams().setSpeed(speed)); } catch(Exception ignored){}
                m.start(); ui.invalidate();
            });
            player.setOnCompletionListener(m -> {
                if (repeatMode == 1) playTrack(t);
                else if (repeatMode == 2) {
                    int n = visibleTracks.indexOf(t);
                    if (n >= 0 && !visibleTracks.isEmpty()) playTrack(visibleTracks.get((n+1)%visibleTracks.size()));
                }
                ui.invalidate();
            });
            player.setOnErrorListener((m,w,e)->{toast("שגיאה בהפעלת הקובץ");return true;});
            player.prepareAsync();
            page = 3;
        } catch (Exception e) { releasePlayer(); toast("לא ניתן להפעיל את הקובץ"); }
    }

    private void playPath(File f) {
        Track t = readMeta(f, currentDir != null && isUnder(f, externalRoot));
        playTrack(t);
    }

    private void releasePlayer() {
        if (player != null) { try { player.release(); } catch(Exception ignored){} player=null; }
    }

    private void openBluetoothItem() {
        if (page == 0) {
            if (selected == 0) toggleBluetooth();
            else startDiscovery();
        } else {
            int idx = selected - 2;
            if (idx >= 0 && idx < btDevices.size()) {
                BluetoothDevice d = btDevices.get(idx);
                if (d.getBondState() != BluetoothDevice.BOND_BONDED) {
                    try { d.createBond(); toast("נשלחה בקשת צימוד"); } catch(Exception e) { openBluetoothSettings(); }
                } else openBluetoothSettings();
            }
        }
    }

    private void refreshBluetooth() {
        btDevices.clear();
        if (bluetooth == null) return;
        try { btDevices.addAll(bluetooth.getBondedDevices()); } catch(Exception ignored){}
        ui.invalidate();
    }

    private boolean containsDevice(BluetoothDevice d) {
        for (BluetoothDevice x : btDevices) if (x.getAddress().equals(d.getAddress())) return true;
        return false;
    }

    private void toggleBluetooth() {
        if (bluetooth == null) { toast("Bluetooth אינו זמין"); return; }
        try {
            if (bluetooth.isEnabled()) bluetooth.disable();
            else startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        } catch (Exception e) { openBluetoothSettings(); }
        ui.invalidate();
    }

    private void startDiscovery() {
        if (bluetooth == null) return;
        try {
            if (!bluetooth.isEnabled()) { toast("הפעל את Bluetooth קודם"); return; }
            btDevices.clear(); btDevices.addAll(bluetooth.getBondedDevices());
            if (bluetooth.isDiscovering()) bluetooth.cancelDiscovery();
            bluetooth.startDiscovery();
            toast("מחפש התקנים...");
            ui.invalidate();
        } catch(Exception e) { openBluetoothSettings(); }
    }

    private void openBluetoothSettings() {
        try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch(Exception ignored){}
    }

    private void openRecorderItem() {
        if (page == 0) {
            if (selected == 0) toggleRecording(); else showLastRecording();
        }
    }

    private void toggleRecording() {
        if (recording) stopRecording();
        else startRecording();
    }

    private void startRecording() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ); return;
        }
        try {
            File dir = new File(internalRoot, "Recordings");
            if (!dir.exists()) dir.mkdirs();
            lastRecording = new File(dir, "REC_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(lastRecording.getAbsolutePath());
            recorder.prepare();
            recorder.start(); recording = true; toast("הקלטה התחילה"); ui.invalidate();
        } catch(Exception e) { recorder=null; toast("לא ניתן להתחיל הקלטה"); }
    }

    private void stopRecording() {
        try { recorder.stop(); } catch(Exception ignored){}
        try { recorder.release(); } catch(Exception ignored){}
        recorder=null; recording=false; toast("ההקלטה נשמרה"); ui.invalidate();
    }

    private void showLastRecording() {
        if (lastRecording == null || !lastRecording.exists()) { toast("עדיין אין הקלטה"); return; }
        playPath(lastRecording);
    }

    private void showFileOptions() {
        if (page != 1 || currentFiles.isEmpty()) return;
        selectedFile = currentFiles.get(selected);
        if (selectedFile.isDirectory()) return;
        final String[] a = {"העתק", "העבר", "מחק", "פרטי הקובץ"};
        new AlertDialog.Builder(this).setTitle(selectedFile.getName())
            .setItems(a, (d,w)-> {
                if (w == 0) chooseDestination(false);
                else if (w == 1) chooseDestination(true);
                else if (w == 2) confirmDelete();
                else showFileInfo();
            }).show();
    }

    private void chooseDestination(boolean move) {
        final File[] roots = externalRoot == null ? new File[]{internalRoot} : new File[]{internalRoot, externalRoot};
        String[] names = externalRoot == null ? new String[]{"זיכרון פנימי"} :
                new String[]{"זיכרון פנימי", "כרטיס זיכרון"};
        new AlertDialog.Builder(this).setTitle(move ? "העבר אל..." : "העתק אל...")
            .setItems(names, (d,w)-> {
                File destDir = roots[w];
                File dest = new File(destDir, selectedFile.getName());
                if (dest.exists()) { toast("קובץ בשם זה כבר קיים"); return; }
                try {
                    copyFile(selectedFile, dest);
                    if (move) selectedFile.delete();
                    refreshFiles(); scanAllMusic(); ui.invalidate();
                    toast(move ? "הקובץ הועבר" : "הקובץ הועתק");
                } catch(Exception e) { toast("הפעולה נכשלה"); }
            }).show();
    }

    private void copyFile(File a, File b) throws IOException {
        InputStream in = new FileInputStream(a); OutputStream out = new FileOutputStream(b);
        byte[] buf = new byte[8192]; int n;
        while((n=in.read(buf))>0) out.write(buf,0,n);
        in.close(); out.close();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this).setTitle("מחיקת קובץ").setMessage("למחוק את " + selectedFile.getName() + "?")
            .setNegativeButton("ביטול", null).setPositiveButton("מחק", (d,w)->{
                if (selectedFile.delete()) { refreshFiles(); scanAllMusic(); ui.invalidate(); toast("הקובץ נמחק"); }
                else toast("לא ניתן למחוק");
            }).show();
    }

    private void showFileInfo() {
        if (selectedFile == null) return;
        new AlertDialog.Builder(this).setTitle("פרטי הקובץ")
            .setMessage("שם: " + selectedFile.getName() + "\nגודל: " + fmtBytes(selectedFile.length()) +
                    "\nמיקום: " + selectedFile.getAbsolutePath())
            .setPositiveButton("אישור", null).show();
    }

    private void playerOptions() {
        final String[] a = {"חזרה: כבוי", "חזרה: שיר", "חזרה: הכל", "מהירות 0.75x", "מהירות 1.0x", "מהירות 1.25x", "מהירות 1.5x", "פרטי הקובץ"};
        new AlertDialog.Builder(this).setTitle("אפשרויות נגן").setItems(a, (d,w)->{
            if (w <= 2) repeatMode = w;
            else if (w == 3) speed = .75f;
            else if (w == 4) speed = 1f;
            else if (w == 5) speed = 1.25f;
            else if (w == 6) speed = 1.5f;
            else if (w == 7) showFileInfo();
            applySpeed();
            ui.invalidate();
        }).show();
    }

    private void applySpeed() {
        if (player != null && Build.VERSION.SDK_INT >= 23) {
            try { player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed)); } catch(Exception ignored){}
        }
    }

    private void seekBy(int ms) {
        if (player == null) return;
        try {
            int p = player.getCurrentPosition();
            int d = player.getDuration();
            player.seekTo(Math.max(0, Math.min(d, p + ms)));
            ui.invalidate();
        } catch(Exception ignored){}
    }

    private void startSeeking(int dir) {
        stopSeeking();
        seekRunnable = new Runnable() {
            @Override public void run() { seekBy(dir * 5000); handler.postDelayed(this, 180); }
        };
        handler.postDelayed(seekRunnable, 450);
    }

    private void stopSeeking() {
        if (seekRunnable != null) handler.removeCallbacks(seekRunnable);
        seekRunnable = null;
    }

    private void backPressedInside() {
        stopSeeking();
        if (page == 3) { releasePlayer(); page = 0; ui.invalidate(); return; }
        if (page == 2) { page = 1; txtScroll = 0; ui.invalidate(); return; }
        if (appIndex == 0 && page == 1) {
            if (currentDir != null && !currentDir.equals(internalRoot) && !currentDir.equals(externalRoot)) {
                currentDir = currentDir.getParentFile(); selected=0; refreshFiles(); ui.invalidate(); return;
            }
            page = 0; selected=0; ui.invalidate(); return;
        }
        if (appIndex == 2 && page == 2) {
            if (musicMode >= 2) { page = 1; selected=0; groupName=""; refreshMusicView(); }
            else { page=0;selected=0;ui.invalidate(); }
            return;
        }
        if (appIndex == 2 && page == 1) { page=0;selected=0;ui.invalidate();return; }
        if (page == 1 || page == 2) { page=0;selected=0;ui.invalidate();return; }
        page=0;selected=0;ui.invalidate();
    }

    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }

    private String fmtBytes(long n) {
        if (n < 1024) return n+" B";
        double v=n/1024.0; if(v<1024)return String.format(Locale.US,"%.1f KB",v);
        v/=1024.0; if(v<1024)return String.format(Locale.US,"%.1f MB",v);
        v/=1024.0; return String.format(Locale.US,"%.1f GB",v);
    }

    private void writeSettings() {
        getPreferences(0).edit().putInt("brightness",brightness).putBoolean("screensaver",screenSaver)
                .putInt("repeat",repeatMode).putFloat("speed",speed).apply();
    }

    @Override protected void onPause() {
        super.onPause();
        writeSettings();
    }

    @Override protected void onDestroy() {
        stopSeeking();
        releasePlayer();
        if (recording) stopRecording();
        if (btReceiver != null) try { unregisterReceiver(btReceiver); } catch(Exception ignored){}
        scanExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() { backPressedInside(); }

    private class Screen extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        Screen(Context c) { super(c); p.setTypeface(Typeface.create("sans",Typeface.NORMAL)); setBackgroundColor(Color.BLACK); }

        float d(float x){return x*getResources().getDisplayMetrics().density;}
        void color(int c){p.setColor(c);p.setStyle(Paint.Style.FILL);}
        void stroke(int c,float sw){p.setColor(c);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(d(sw));}
        void rr(Canvas c,float l,float t,float r,float b,int col,float rad){color(col);c.drawRoundRect(l,t,r,b,d(rad),d(rad),p);}
        void text(Canvas c,String s,float x,float y,float z,int col,Paint.Align a){color(col);p.setTextSize(d(z));p.setTextAlign(a);p.setTypeface(Typeface.create("sans",Typeface.NORMAL));c.drawText(s,x,y,p);}
        String cut(String s,int n){ if(s==null)return ""; return s.length()>n?s.substring(0,n-1)+"…":s; }

        @Override protected void onDraw(Canvas c) {
            float w=getWidth(), h=getHeight();
            color(0xff050606); c.drawRect(0,0,w,h,p);
            rr(c,d(10),d(5),w-d(10),h-d(5),0xff202222,22);
            stroke(0xff3f4444,1.5f);c.drawRoundRect(d(10),d(5),w-d(10),h-d(5),d(22),d(22),p);

            float displayTop=d(32), displayBottom=Math.min(h*.50f,d(510));
            rr(c,d(40),displayTop,w-d(40),displayBottom,Color.WHITE,0);
            drawDisplay(c,d(40),displayTop,w-d(40),displayBottom);

            text(c,"IBusiness eXtra",w/2,d(565),32,Color.WHITE,Paint.Align.CENTER);
            rr(c,w-d(132),d(542),w-d(55),d(578),0xffeeeeee,2);
            text(c,"16GB",w-d(93),d(566),17,0xff333333,Paint.Align.CENTER);

            text(c,"▐",d(109),d(650),31,Color.WHITE,Paint.Align.CENTER);
            text(c,"⚙",w-d(91),d(650),31,0xffeeeeee,Paint.Align.CENTER);

            float cy=Math.min(h-d(190),d(780)), cx=w/2;
            stroke(0xffeeeeee,2);
            c.drawArc(cx-d(78),cy-d(128),cx+d(78),cy+d(128),208,124,false,p);
            c.drawArc(cx-d(78),cy-d(128),cx+d(78),cy+d(128),-28,124,false,p);
            text(c,"▲",cx,cy-d(91),28,Color.WHITE,Paint.Align.CENTER);
            text(c,"▼",cx,cy+d(121),28,Color.WHITE,Paint.Align.CENTER);
            text(c,"◀",cx-d(98),cy+d(6),25,Color.WHITE,Paint.Align.CENTER);
            text(c,"▶",cx+d(98),cy+d(6),25,Color.WHITE,Paint.Align.CENTER);
            stroke(0xffeeeeee,2);
            c.drawCircle(cx,cy,d(33),p);
            text(c,"♢",cx,cy+d(10),31,Color.WHITE,Paint.Align.CENTER);
        }

        private void drawDisplay(Canvas c,float l,float t,float r,float b) {
            if (appIndex == 0) drawFiles(c,l,t,r,b);
            else if (appIndex == 1) drawSettings(c,l,t,r,b);
            else if (appIndex == 2) drawMusic(c,l,t,r,b);
            else if (appIndex == 3) drawBt(c,l,t,r,b);
            else drawRecorder(c,l,t,r,b);
        }

        private void title(Canvas c,String s,float l,float r,float y){
            text(c,s,(l+r)/2,y,24,0xff111111,Paint.Align.CENTER);
        }

        private void line(Canvas c,String s,float x,float y,boolean sel){
            if(sel) rr(c,x-d(8),y-d(27),getWidth()-d(52),y+d(8),0xff111111,2);
            text(c,s,getWidth()-d(60),y,17,sel?Color.WHITE:0xff111111,Paint.Align.RIGHT);
        }

        private void drawFiles(Canvas c,float l,float t,float r,float b){
            if(page==0){
                title(c,"סייר קבצים",l,r,t+d(36));
                line(c,"1. זיכרון פנימי",l,t+d(90),selected==0);
                line(c,"2. כרטיס זיכרון",l,t+d(137),selected==1);
            } else if(page==2) {
                title(c,"טקסט",l,r,t+d(36));
                String[] lines=txtContent.split("\\r?\\n");
                float y=t+d(75)-txtScroll;
                for(String s:lines){
                    if(y>b-d(18)){y+=d(26);continue;}
                    if(y>t+d(58)) text(c,cut(s,38),r-d(12),y,12,0xff111111,Paint.Align.RIGHT);
                    y+=d(22);
                }
            } else {
                title(c,cut(pathLabel(currentDir),24),l,r,t+d(36));
                if(currentFiles.isEmpty()){text(c,"אין קבצים נתמכים", (l+r)/2,t+d(92),15,0xff333333,Paint.Align.CENTER);return;}
                int first=Math.max(0,Math.min(selected-5,Math.max(0,currentFiles.size()-8)));
                float y=t+d(73);
                for(int i=first;i<Math.min(currentFiles.size(),first+8);i++){
                    File f=currentFiles.get(i);
                    String prefix=f.isDirectory()?"▸ ":"";
                    if(isAudio(f))prefix="♫ ";
                    if(isTxt(f))prefix="TXT ";
                    line(c,prefix+cut(f.getName(),30),l,y,selected==i);
                    y+=d(34);
                }
            }
        }

        private void drawSettings(Canvas c,float l,float t,float r,float b){
            if(page==0){
                title(c,"הגדרות",l,r,t+d(36));
                line(c,"בהירות: "+brightness+"%",l,t+d(90),selected==0);
                line(c,"שומר מסך: "+(screenSaver?"פעיל":"כבוי"),l,t+d(137),selected==1);
                line(c,"פרטי זיכרון",l,t+d(184),selected==2);
            }
        }

        private void drawMusic(Canvas c,float l,float t,float r,float b){
            if(page==0){
                title(c,"מוזיקה",l,r,t+d(36));
                line(c,"1. כל השירים",l,t+d(85),selected==0);
                line(c,"2. כרטיס זיכרון",l,t+d(122),selected==1);
                line(c,"3. אמנים",l,t+d(159),selected==2);
                line(c,"4. אלבומים",l,t+d(196),selected==3);
            } else if(page==1){
                title(c, musicMode==2?"אמנים":"אלבומים",l,r,t+d(36));
                if(groups.isEmpty()){text(c,"אין נתונים", (l+r)/2,t+d(95),15,0xff111111,Paint.Align.CENTER);return;}
                int first=Math.max(0,Math.min(selected-5,Math.max(0,groups.size()-8)));float y=t+d(72);
                for(int i=first;i<Math.min(groups.size(),first+8);i++){line(c,cut(groups.get(i),30),l,y,selected==i);y+=d(34);}
            } else if(page==2){
                title(c, groupName.isEmpty()?(musicMode==1?"כרטיס זיכרון":"כל השירים"):cut(groupName,24),l,r,t+d(36));
                if(visibleTracks.isEmpty()){text(c,"אין שירים", (l+r)/2,t+d(95),15,0xff111111,Paint.Align.CENTER);return;}
                int first=Math.max(0,Math.min(selected-5,Math.max(0,visibleTracks.size()-8)));float y=t+d(72);
                for(int i=first;i<Math.min(visibleTracks.size(),first+8);i++){line(c,cut(visibleTracks.get(i).title,28),l,y,selected==i);y+=d(34);}
            } else {
                title(c,"מנגן עכשיו",l,r,t+d(36));
                if(selectedFile==null){text(c,"אין רצועה", (l+r)/2,t+d(95),15,0xff111111,Paint.Align.CENTER);return;}
                text(c,cut(selectedFile.getName(),30),(l+r)/2,t+d(88),16,0xff111111,Paint.Align.CENTER);
                long pp=player==null?0:player.getCurrentPosition(), dd=player==null?0:player.getDuration();
                rr(c,l+d(22),t+d(118),r-d(22),t+d(125),0xffbbbbbb,3);
                if(dd>0) rr(c,l+d(22),t+d(118),l+d(22)+(r-l-d(44))*((float)pp/(float)dd),t+d(125),0xff222222,3);
                text(c,formatTime(pp),l+d(22),t+d(145),11,0xff333333,Paint.Align.LEFT);
                text(c,formatTime(dd),r-d(22),t+d(145),11,0xff333333,Paint.Align.RIGHT);
                text(c,player!=null&&player.isPlaying()?"▶":"Ⅱ",(l+r)/2,t+d(178),24,0xff111111,Paint.Align.CENTER);
                text(c,"חזרה: "+(repeatMode==0?"כבויה":repeatMode==1?"שיר":"הכל")+"   מהירות: "+speed+"x",(l+r)/2,t+d(205),11,0xff555555,Paint.Align.CENTER);
                postInvalidateDelayed(500);
            }
        }

        private void drawBt(Canvas c,float l,float t,float r,float b){
            if(page==0){
                title(c,"בלוטוס",l,r,t+d(36));
                boolean on=bluetooth!=null&&bluetooth.isEnabled();
                line(c,"Bluetooth: "+(on?"פועל":"כבוי"),l,t+d(85),selected==0);
                line(c,"חפש התקנים",l,t+d(122),selected==1);
                int y=159, i=2;
                for(BluetoothDevice d:btDevices){line(c,cut(safeName(d),26),l,t+d(y),selected==i);y+=34;i++;}
            }
        }

        private String safeName(BluetoothDevice d){try{return d.getName()==null?d.getAddress():d.getName();}catch(Exception e){return d.getAddress();}}

        private void drawRecorder(Canvas c,float l,float t,float r,float b){
            title(c,"רשמקול",l,r,t+d(36));
            line(c,recording?"■ עצור הקלטה":"● צור הקלטה",l,t+d(90),selected==0);
            line(c,"נגן הקלטה אחרונה",l,t+d(137),selected==1);
            if(recording) text(c,"מקליט עכשיו…",(l+r)/2,t+d(190),15,0xffaa0000,Paint.Align.CENTER);
        }

        private String formatTime(long ms){ long s=Math.max(0,ms/1000); return String.format(Locale.US,"%d:%02d",s/60,s%60); }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            float x=e.getX(), y=e.getY(), w=getWidth(), h=getHeight();
            float cy=Math.min(h-d(190),d(780)), cx=w/2;
            if(e.getAction()==MotionEvent.ACTION_DOWN){
                pressStarted=System.currentTimeMillis();
                if(page==3 && Math.abs(x-(cx-d(98)))<d(55)){startSeeking(-1);return true;}
                if(page==3 && Math.abs(x-(cx+d(98)))<d(55)){startSeeking(1);return true;}
                return true;
            }
            if(e.getAction()==MotionEvent.ACTION_UP){
                stopSeeking();
                long held=System.currentTimeMillis()-pressStarted;
                if(y<d(710) && x<d(150)){backPressedInside();return true;}
                if(y<d(710) && x>w-d(150)){ showTopOptions(); return true; }
                if(Math.abs(x-cx)<d(55)&&Math.abs(y-cy)<d(55)){openSelected();return true;}
                if(Math.abs(x-cx)<d(75)&&y<cy-d(55)&&y>cy-d(155)){move(-1);return true;}
                if(Math.abs(x-cx)<d(75)&&y>cy+d(55)&&y<cy+d(155)){move(1);return true;}
                if(Math.abs(y-cy)<d(65)&&x<cx-d(55)){ 
                    if(page==3){seekBy(held>550?-10000:-5000);}
                    else setApp(appIndex-1);
                    return true;
                }
                if(Math.abs(y-cy)<d(65)&&x>cx+d(55)){
                    if(page==3){seekBy(held>550?10000:5000);}
                    else setApp(appIndex+1);
                    return true;
                }
                return true;
            }
            return true;
        }

        private void showTopOptions() {
            if(appIndex==0 && page==1) showFileOptions();
            else if(appIndex==2 && page==3) playerOptions();
            else if(appIndex==3) openBluetoothSettings();
            else if(appIndex==4 && page==0 && lastRecording!=null) playPath(lastRecording);
        }

        private void move(int d){
            int n=displayCount();
            if(n<=0){selected=0;invalidate();return;}
            selected=(selected+d+n)%n;
            if(page==2 && appIndex==0 && txtContent.length()>0){
                txtScroll=Math.max(0,Math.min(Math.max(0,txtContent.split("\\n").length*22-300),txtScroll+d*80));
            }
            invalidate();
        }
    }
}