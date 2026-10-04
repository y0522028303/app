package com.nova.mp3;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.Toast;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ = 77;
    private static final int PAGE_HOME = 0;
    private static final int PAGE_MENU = 1;
    private static final int PAGE_LIST = 2;
    private static final int PAGE_TEXT = 3;
    private static final int PAGE_PLAYER = 4;
    private final String[] APPS = {"סייר קבצים", "הגדרות", "מוזיקה", "בלוטוס", "רשמקול"};
    private final String[] AUDIO_EXT = {".mp3",".m4a",".aac",".wav",".ogg",".3gp",".flac",".amr"};
    private final ArrayList<File> currentFiles = new ArrayList<>();
    private final ArrayList<Track> allTracks = new ArrayList<>();
    private final ArrayList<Track> visibleTracks = new ArrayList<>();
    private final ArrayList<Track> recentTracks = new ArrayList<>();
    private final ArrayList<String> groups = new ArrayList<>();
    private final ArrayList<String> groupTracks = new ArrayList<>();
    private final ArrayList<BluetoothDevice> btDevices = new ArrayList<>();
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private Handler handler = new Handler(Looper.getMainLooper());

    private Screen ui;
    private MediaPlayer player;
    private MediaRecorder recorder;
    private Bitmap albumArt;
    private Track currentTrack;
    private long pressStarted;
    private Runnable seekRunnable;
    private Runnable navRepeatRunnable;
    private int navRepeatDir = 0;
    private boolean navLongTriggered = false;
    private int returnPageAfterPlayer = 1;
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
        brightness = getPreferences(0).getInt("brightness", 100);
        screenSaver = getPreferences(0).getBoolean("screensaver", true);

        ui = new Screen(this);
        setContentView(ui);
        applyDisplaySettings();

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

            // Remove duplicate paths and sort by title.
            LinkedHashMap<String, Track> unique = new LinkedHashMap<>();
            for (Track t : found) unique.put(t.path, t);
            found.clear();
            found.addAll(unique.values());
            found.sort(Comparator.comparing(t -> t.title.toLowerCase(Locale.ROOT)));

            runOnUiThread(() -> {
                allTracks.clear();
                allTracks.addAll(found);
                refreshMusicView();
            });
        });
    }

    // The music database scans only the user's shared storage.
    // Android/system/app/cache folders are deliberately excluded.
    private static final HashSet<String> BLOCKED_AUDIO_DIRS = new HashSet<>(Arrays.asList(
            "android", "system", "data", "obb", "cache", "caches", "lost.dir",
            "system volume information", "$recycle.bin", ".thumbnails", "thumbnails",
            "notifications", "ringtones", "alarms", ".nomedia"
    ));

    private void scanMusicDir(File dir, boolean ext, ArrayList<Track> out, int depth) {
        if (dir == null || !dir.exists() || depth > 12 || out.size() > 4000) return;
        String lowerName = dir.getName().toLowerCase(Locale.ROOT);
        if (BLOCKED_AUDIO_DIRS.contains(lowerName) || dir.getName().startsWith(".")) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                String n = f.getName().toLowerCase(Locale.ROOT);
                if (!BLOCKED_AUDIO_DIRS.contains(n) && !f.getName().startsWith(".")) {
                    scanMusicDir(f, ext, out, depth + 1);
                }
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
            visibleTracks.addAll(recentTracks);
        } else if (musicMode == 1) {
            visibleTracks.addAll(allTracks);
        } else if (musicMode == 2) {
            for (Track t : allTracks) if (t.external) visibleTracks.add(t);
        } else if (musicMode == 3) {
            TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Track t : allTracks) set.add(t.artist);
            groups.addAll(set);
            if (!groupName.isEmpty()) for (Track t : allTracks)
                if (t.artist.equals(groupName)) visibleTracks.add(t);
        } else if (musicMode == 4) {
            TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (Track t : allTracks) set.add(t.album);
            groups.addAll(set);
            if (!groupName.isEmpty()) for (Track t : allTracks)
                if (t.album.equals(groupName)) visibleTracks.add(t);
        }

        selected = Math.max(0, Math.min(selected, Math.max(0, displayCount() - 1)));
        ui.invalidate();
    }

    private int displayCount() {
        if (page == PAGE_HOME) return 1;
        if (appIndex == 0) {
            if (page == PAGE_MENU) return 2;
            if (page == PAGE_LIST) return currentFiles.size();
        } else if (appIndex == 1) {
            if (page == PAGE_MENU) return 3;
        } else if (appIndex == 2) {
            if (page == PAGE_MENU) return 8;
            if (page == PAGE_LIST) return groups.isEmpty() ? 0 : groups.size();
            if (page == PAGE_TEXT) return visibleTracks.size();
        } else if (appIndex == 3) {
            if (page == PAGE_MENU) return 2 + btDevices.size();
        } else if (appIndex == 4) {
            if (page == PAGE_MENU) return 2;
        }
        return 1;
    }

    private void setApp(int i) {
        stopSeeking();
        if (ui != null) ui.stopNavRepeat();
        page = PAGE_HOME;
        selected = 0;
        txtScroll = 0;
        groupName = "";
        appIndex = (i + APPS.length) % APPS.length;

        if (appIndex == 0) currentDir = internalRoot;
        if (appIndex == 2) {
            musicMode = 0;
            refreshMusicView();
        }
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
        if (page == PAGE_PLAYER) {
            togglePlayerPlayback();
            return;
        }
        if (appIndex == 0) openFileItem();
        else if (appIndex == 1) openSetting();
        else if (appIndex == 2) openMusicItem();
        else if (appIndex == 3) openBluetoothItem();
        else if (appIndex == 4) openRecorderItem();
        ui.invalidate();
    }

    private void openFileItem() {
        if (page == PAGE_HOME) {
            page = PAGE_MENU;
            selected = 0;
            ui.invalidate();
            return;
        }
        if (page == PAGE_MENU) {
            currentDir = selected == 0 ? internalRoot : externalRoot;
            if (currentDir != null) {
                page = PAGE_LIST;
                selected = 0;
                refreshFiles();
            }
            ui.invalidate();
            return;
        }
        if (page != PAGE_LIST || currentFiles.isEmpty()) return;

        File f = currentFiles.get(selected);
        if (f.isDirectory()) {
            currentDir = f;
            selected = 0;
            refreshFiles();
            breadcrumb = f.getName();
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
        if (page == PAGE_HOME) {
            page = PAGE_MENU;
            selected = 0;
        } else if (page == PAGE_MENU) {
            if (selected == 0) {
                brightness = brightness == 100 ? 50 : brightness == 50 ? 25 : 100;
                applyDisplaySettings();
            } else if (selected == 1) {
                screenSaver = !screenSaver;
                applyDisplaySettings();
            } else {
                showMemoryInfo();
            }
        }
        ui.invalidate();
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
        if (page == PAGE_PLAYER) {
            togglePlayerPlayback();
            return;
        }
        if (page == PAGE_HOME) {
            page = PAGE_MENU;
            selected = 0;
            return;
        }

        if (page == PAGE_MENU) {
            musicMode = selected;
            selected = 0;
            groupName = "";

            if (musicMode == 0) {
                visibleTracks.clear();
                visibleTracks.addAll(recentTracks);
                page = PAGE_TEXT;
            } else if (musicMode == 1) {
                page = PAGE_TEXT;
                refreshMusicView();
            } else if (musicMode == 2) {
                page = PAGE_TEXT;
                refreshMusicView();
            } else if (musicMode == 3 || musicMode == 4) {
                page = PAGE_LIST;
                refreshMusicView();
            } else if (musicMode == 5 || musicMode == 6) {
                page = PAGE_LIST;
                groups.clear();
                groups.add(musicMode == 5 ? "סגנונות עדיין לא זמינים" : "אין רשימות השמעה");
            } else {
                page = PAGE_LIST;
                refreshMusicView();
            }
            ui.invalidate();
            return;
        }

        if (page == PAGE_LIST && (musicMode == 3 || musicMode == 4)) {
            if (groups.isEmpty()) return;
            groupName = groups.get(selected);
            selected = 0;
            page = PAGE_TEXT;
            refreshMusicView();
            return;
        }

        if (page == PAGE_TEXT) {
            if (!visibleTracks.isEmpty() && selected < visibleTracks.size()) {
                playTrack(visibleTracks.get(selected));
            }
        }
    }

    private void togglePlayerPlayback() {
        if (player == null) return;
        try {
            if (player.isPlaying()) player.pause();
            else player.start();
        } catch (Exception ignored) {}
        ui.invalidate();
    }

    private void playTrack(Track t) {
        returnPageAfterPlayer = page;
        selectedFile = new File(t.path);
        currentTrack = t;

        recentTracks.removeIf(x -> x.path.equals(t.path));
        recentTracks.add(0, t);
        while (recentTracks.size() > 20) recentTracks.remove(recentTracks.size() - 1);

        albumArt = null;
        try {
            MediaMetadataRetriever meta = new MediaMetadataRetriever();
            meta.setDataSource(t.path);
            byte[] art = meta.getEmbeddedPicture();
            if (art != null) albumArt = BitmapFactory.decodeByteArray(art, 0, art.length);
            meta.release();
        } catch (Exception ignored) {}

        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(t.path);
            player.setOnPreparedListener(m -> {
                try { m.setPlaybackParams(m.getPlaybackParams().setSpeed(speed)); }
                catch(Exception ignored){}
                m.start();
                ui.invalidate();
            });
            player.setOnCompletionListener(m -> {
                if (repeatMode == 1) {
                    playTrack(t);
                } else if (repeatMode == 2 && !visibleTracks.isEmpty()) {
                    int n = visibleTracks.indexOf(t);
                    if (n >= 0) playTrack(visibleTracks.get((n + 1) % visibleTracks.size()));
                }
                ui.invalidate();
            });
            player.setOnErrorListener((m,w,e)->{toast("שגיאה בהפעלת הקובץ");return true;});
            player.prepareAsync();
            page = PAGE_PLAYER;
        } catch (Exception e) {
            releasePlayer();
            toast("לא ניתן להפעיל את הקובץ");
        }
    }

    private void playPath(File f) {
        Track t = readMeta(f, currentDir != null && isUnder(f, externalRoot));
        playTrack(t);
    }

    private void releasePlayer() {
        if (player != null) { try { player.release(); } catch(Exception ignored){} player=null; }
    }

    private void openBluetoothItem() {
        if (page == PAGE_HOME) {
            page = PAGE_MENU;
            selected = 0;
            refreshBluetooth();
            ui.invalidate();
            return;
        }
        if (page != PAGE_MENU) return;

        if (selected == 0) toggleBluetooth();
        else {
            int idx = selected - 2;
            if (idx < 0) startDiscovery();
            else if (idx < btDevices.size()) {
                BluetoothDevice d = btDevices.get(idx);
                if (d.getBondState() != BluetoothDevice.BOND_BONDED) {
                    try { d.createBond(); toast("נשלחה בקשת צימוד"); }
                    catch(Exception e) { openBluetoothSettings(); }
                } else {
                    openBluetoothSettings();
                }
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
        if (page == PAGE_HOME) {
            page = PAGE_MENU;
            selected = 0;
            ui.invalidate();
            return;
        }
        if (page == PAGE_MENU) {
            if (selected == 0) toggleRecording();
            else showLastRecording();
            ui.invalidate();
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
        if (page != PAGE_LIST || currentFiles.isEmpty()) return;
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
        if (ui != null) ui.stopNavRepeat();

        if (page == PAGE_PLAYER) {
            releasePlayer();
            page = returnPageAfterPlayer;
            if (appIndex == 0) refreshFiles();
            if (appIndex == 2) refreshMusicView();
            ui.invalidate();
            return;
        }

        if (appIndex == 0) {
            if (page == PAGE_TEXT) {
                page = PAGE_LIST;
                txtScroll = 0;
            } else if (page == PAGE_LIST) {
                if (currentDir != null && !currentDir.equals(internalRoot)
                        && (externalRoot == null || !currentDir.equals(externalRoot))) {
                    currentDir = currentDir.getParentFile();
                    selected = 0;
                    refreshFiles();
                } else {
                    page = PAGE_MENU;
                    selected = 0;
                }
            } else if (page == PAGE_MENU) {
                page = PAGE_HOME;
                selected = 0;
            }
            ui.invalidate();
            return;
        }

        if (appIndex == 2) {
            if (page == PAGE_TEXT) {
                if (musicMode == 0 || musicMode == 1 || musicMode == 2) {
                    page = PAGE_MENU;
                } else {
                    page = PAGE_LIST;
                }
                selected = 0;
                refreshMusicView();
            } else if (page == PAGE_LIST) {
                page = PAGE_MENU;
                selected = 0;
                refreshMusicView();
            } else if (page == PAGE_MENU) {
                page = PAGE_HOME;
                selected = 0;
            }
            ui.invalidate();
            return;
        }

        if (page == PAGE_MENU) {
            page = PAGE_HOME;
            selected = 0;
            ui.invalidate();
        }
    }

    private String safeName(BluetoothDevice d){
        try { return d.getName()==null ? d.getAddress() : d.getName(); }
        catch(Exception e){ return d.getAddress(); }
    }

    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_SHORT).show(); }

    private String fmtBytes(long n) {
        if (n < 1024) return n+" B";
        double v=n/1024.0; if(v<1024)return String.format(Locale.US,"%.1f KB",v);
        v/=1024.0; if(v<1024)return String.format(Locale.US,"%.1f MB",v);
        v/=1024.0; return String.format(Locale.US,"%.1f GB",v);
    }

    private void applyDisplaySettings() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.max(0.05f, Math.min(1.0f, brightness / 100f));
        getWindow().setAttributes(lp);
        if (screenSaver) ui.setKeepScreenOn(false); else ui.setKeepScreenOn(true);
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
        if (ui != null) ui.stopNavRepeat();
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
            color(0xff030405); c.drawRect(0,0,w,h,p);
            rr(c,d(10),d(5),w-d(10),h-d(5),0xff111516,22);
            stroke(0xff3f4547,1.5f);
            c.drawRoundRect(d(10),d(5),w-d(10),h-d(5),d(22),d(22),p);

            float displayTop=d(32), displayBottom=Math.min(h*.50f,d(510));
            drawDisplayFrame(c,d(40),displayTop,w-d(40),displayBottom);

            text(c,"IBusiness eXtra",w/2,d(565),32,0xffeeeeee,Paint.Align.CENTER);
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

        private void drawDisplayFrame(Canvas c,float l,float t,float r,float b){
            LinearGradient bg = new LinearGradient(l,t,r,b,0xff6f839a,0xff123d66,Shader.TileMode.CLAMP);
            p.setShader(bg); p.setStyle(Paint.Style.FILL);
            c.drawRect(l,t,r,b,p); p.setShader(null);
            drawCity(c,l,t,r,b);
            drawStatus(c,l,t,r);
            drawDisplay(c,l,t,r,b);
        }

        private void drawCity(Canvas c,float l,float t,float r,float b){
            int bottom=(int)b;
            color(0x553b86c4);
            c.drawRect(l,t,r,b,p);
            Random rrnd=new Random(41);
            for(int i=0;i<24;i++){
                float bw=d(9+rrnd.nextInt(23));
                float bh=d(45+rrnd.nextInt(145));
                float x=l+d(4)+i*((r-l-d(8))/24f);
                color(0xAA154f85);
                c.drawRect(x,b-bh,x+bw,b,p);
                color(0x88e9f2ff);
                for(int wy=0;wy<4;wy++) for(int wx=0;wx<2;wx++){
                    float yy=b-bh+d(10)+wy*d(18), xx=x+d(4)+wx*d(7);
                    if(yy<b-d(6)) c.drawRect(xx,yy,xx+d(3),yy+d(5),p);
                }
            }
            color(0x6637a4dd); c.drawRect(l,b-d(65),r,b,p);
            stroke(0x5568b8df,1); c.drawLine(l,b-d(65),r,b-d(65),p);
        }

        private void drawStatus(Canvas c,float l,float t,float r){
            color(0xEEEEF2F7); c.drawRect(l,t,r,t+d(23),p);
            Calendar now=Calendar.getInstance();
            String tm=String.format(Locale.getDefault(),"%02d:%02d",now.get(Calendar.HOUR_OF_DAY),now.get(Calendar.MINUTE));
            text(c,tm,r-d(9),t+d(16),10,0xff4d5a64,Paint.Align.RIGHT);
            text(c,"▮▮  ⌁",r-d(58),t+d(16),8,0xff5c6d78,Paint.Align.RIGHT);
            stroke(0xff61717c,1); c.drawRect(l+d(7),t+d(7),l+d(24),t+d(16),p);
        }

        private void drawDisplay(Canvas c,float l,float t,float r,float b){
            if(page==PAGE_PLAYER){ drawPlayerScreen(c,l,t,r,b); return; }
            if(appIndex==0) drawFiles(c,l,t,r,b);
            else if(appIndex==1) drawSettings(c,l,t,r,b);
            else if(appIndex==2) drawMusic(c,l,t,r,b);
            else if(appIndex==3) drawBt(c,l,t,r,b);
            else drawRecorder(c,l,t,r,b);
        }

                private void drawPlayerScreen(Canvas c,float l,float t,float r,float b){
            menuHeader(c,"מנגן עכשיו",l,t,r);
            color(0x5539a5de); c.drawRect(l,t+d(63),r,b,p);
            if(selectedFile==null){
                text(c,"אין רצועה",(l+r)/2,t+d(110),15,Color.WHITE,Paint.Align.CENTER);
                return;
            }

            float artL=l+d(30), artT=t+d(73), artR=r-d(30), artB=t+d(184);
            color(0xffdce4ea); c.drawRect(artL,artT,artR,artB,p);
            if(albumArt!=null){
                c.drawBitmap(albumArt,null,new RectF(artL,artT,artR,artB),p);
            } else {
                drawIcon(c,2,(artL+artR)/2,(artT+artB)/2,
                        Math.min(artR-artL,artB-artT)*.34f,0xff6d7f8c);
            }

            text(c,cut(selectedFile.getName(),31),(l+r)/2,t+d(207),14,Color.WHITE,Paint.Align.CENTER);
            if(currentTrack!=null){
                text(c,cut(currentTrack.artist,27),(l+r)/2,t+d(225),10,0xffd8e6ee,Paint.Align.CENTER);
                text(c,cut(currentTrack.album,27),(l+r)/2,t+d(241),9,0xffc1d3dc,Paint.Align.CENTER);
            }

            long pp=player==null?0:player.getCurrentPosition();
            long dd=player==null?0:player.getDuration();
            rr(c,l+d(18),t+d(255),r-d(18),t+d(262),0xaae8f0f6,3);
            if(dd>0) rr(c,l+d(18),t+d(255),
                    l+d(18)+(r-l-d(36))*Math.min(1f,pp/(float)dd),
                    t+d(262),0xffff5aa6,3);

            text(c,"✕",l+d(37),t+d(285),17,Color.WHITE,Paint.Align.CENTER);
            text(c,"≡",l+d(79),t+d(285),17,Color.WHITE,Paint.Align.CENTER);
            text(c,"♥",(l+r)/2,t+d(285),17,Color.WHITE,Paint.Align.CENTER);
            text(c,"↺",r-d(39),t+d(285),17,Color.WHITE,Paint.Align.CENTER);

            text(c,formatTime(pp),l+d(19),t+d(301),9,Color.WHITE,Paint.Align.LEFT);
            text(c,formatTime(dd),r-d(19),t+d(301),9,Color.WHITE,Paint.Align.RIGHT);
            text(c,player!=null&&player.isPlaying()?"Ⅱ":"▶",(l+r)/2,t+d(302),24,Color.WHITE,Paint.Align.CENTER);
            text(c,"חזרה: "+(repeatMode==0?"כבויה":repeatMode==1?"שיר":"הכל")+"   "+speed+"x",
                    (l+r)/2,t+d(323),9,Color.WHITE,Paint.Align.CENTER);
            postInvalidateDelayed(500);
        }

        private void drawHomeTile(Canvas c,String label,int kind,float l,float t,float r,float b){
            float wl=r-l, wt=b-t;
            float boxL=l+wl*.12f, boxR=r-wl*.12f, boxT=t+d(44), boxB=t+wt*.64f;

            color(0xf2f4f6f7);
            c.drawRect(boxL,boxT,boxR,boxB,p);
            color(0xffdfe5e9);
            c.drawRect(boxL+d(12),boxT+d(12),boxR-d(12),boxB-d(12),p);
            drawIcon(c,kind,(boxL+boxR)/2,(boxT+boxB)/2,
                    Math.min(boxR-boxL,boxB-boxT)*.40f,0xffbcc4ca);
            text(c,label,(l+r)/2,boxB+d(42),22,Color.WHITE,Paint.Align.CENTER);
        }

        private void drawIcon(Canvas c,int kind,float x,float y,float s,int col){
            stroke(col,2);
            if(kind==0){
                c.drawRect(x-s*.48f,y-s*.27f,x+s*.48f,y+s*.42f,p);
                c.drawLine(x-s*.48f,y-s*.27f,x-s*.08f,y-s*.42f,p);
                c.drawLine(x-s*.08f,y-s*.42f,x+s*.18f,y-s*.27f,p);
                c.drawLine(x-s*.35f,y-s*.12f,x+s*.35f,y-s*.12f,p);
            } else if(kind==1){
                c.drawCircle(x,y,s*.34f,p);
                c.drawCircle(x,y,s*.12f,p);
                for(int i=0;i<8;i++){double a=i*Math.PI/4;c.drawLine(x+(float)Math.cos(a)*s*.34f,y+(float)Math.sin(a)*s*.34f,x+(float)Math.cos(a)*s*.49f,y+(float)Math.sin(a)*s*.49f,p);}
            } else if(kind==2){
                c.drawOval(x-s*.32f,y-s*.45f,x+s*.32f,y+s*.08f,p);
                c.drawLine(x+s*.32f,y-s*.1f,x+s*.32f,y+s*.48f,p);
                c.drawLine(x+s*.32f,y+s*.48f,x+s*.02f,y+s*.48f,p);
                c.drawLine(x+s*.02f,y+s*.48f,x+s*.02f,y+s*.25f,p);
            } else if(kind==3){
                Path q=new Path();q.moveTo(x-s*.05f,y-s*.55f);q.lineTo(x+s*.37f,y-s*.22f);q.lineTo(x+s*.05f,y-s*.02f);q.lineTo(x+s*.4f,y+s*.27f);q.lineTo(x-s*.05f,y+s*.55f);q.close();c.drawPath(q,p);
                c.drawLine(x-s*.22f,y-s*.33f,x-s*.22f,y+s*.35f,p);
            } else {
                c.drawRoundRect(x-s*.30f,y-s*.42f,x+s*.30f,y+s*.25f,d(8),d(8),p);
                c.drawLine(x-s*.08f,y+s*.25f,x-s*.08f,y+s*.50f,p);
                c.drawLine(x+s*.08f,y+s*.25f,x+s*.08f,y+s*.50f,p);
                c.drawLine(x-s*.21f,y+s*.50f,x+s*.21f,y+s*.50f,p);
            }
        }

        private void menuHeader(Canvas c,String s,float l,float t,float r){
            color(0xffa235a5); c.drawRect(l,t+d(23),r,t+d(63),p);
            text(c,s,r-d(12),t+d(49),17,Color.WHITE,Paint.Align.RIGHT);
            text(c,"●",l+d(13),t+d(49),9,Color.WHITE,Paint.Align.CENTER);
        }

        private void menuRow(Canvas c,String s,float l,float y,float r,boolean sel,boolean note){
            int bg=sel?0xffa539a7:0x661a86c4;
            color(bg); c.drawRect(l,y-d(20),r,y+d(13),p);
            if(note) text(c,"♪",l+d(13),y+1,15,Color.WHITE,Paint.Align.CENTER);
            text(c,s,r-d(14),y+2,15,Color.WHITE,Paint.Align.RIGHT);
            stroke(0x5597d7ef,1); c.drawLine(l,y+d(13),r,y+d(13),p);
        }

        private void drawFiles(Canvas c,float l,float t,float r,float b){
            if(page==PAGE_HOME){ drawHomeTile(c,"סייר קבצים",0,l,t,r,b); return; }

            if(page==PAGE_MENU){
                menuHeader(c,"סייר קבצים",l,t,r);
                color(0x5539a5de); c.drawRect(l,t+d(63),r,b,p);
                menuRow(c,"1. אחסון פנימי",l,t+d(91),r,selected==0,false);
                menuRow(c,"2. אחסון חיצוני",l,t+d(125),r,selected==1,false);
                return;
            }

            if(page==PAGE_TEXT){
                menuHeader(c,"קריאת טקסט",l,t,r);
                color(0x88448fc4); c.drawRect(l,t+d(63),r,b,p);
                String[] ls=txtContent.split("\\r?\\n");
                float y=t+d(89)-txtScroll;
                for(String s:ls){
                    if(y>b-d(16)){y+=d(22);continue;}
                    if(y>t+d(72)) text(c,cut(s,42),r-d(10),y,11,Color.WHITE,Paint.Align.RIGHT);
                    y+=d(22);
                }
                return;
            }

            menuHeader(c,cut(pathLabel(currentDir),22),l,t,r);
            color(0x5539a5de); c.drawRect(l,t+d(63),r,b,p);
            if(currentFiles.isEmpty()){
                text(c,"אין קבצים נתמכים",(l+r)/2,t+d(112),15,Color.WHITE,Paint.Align.CENTER);
                return;
            }
            int first=Math.max(0,Math.min(selected-6,Math.max(0,currentFiles.size()-9)));
            float y=t+d(87);
            for(int i=first;i<Math.min(currentFiles.size(),first+9);i++){
                File f=currentFiles.get(i);
                String icon=f.isDirectory()?"▣ ":isAudio(f)?"♪ ":"TXT ";
                menuRow(c,icon+cut(f.getName(),29),l,y,r,selected==i,isAudio(f));
                y+=d(33);
            }
        }

        private void drawSettings(Canvas c,float l,float t,float r,float b){
            if(page==PAGE_HOME){ drawHomeTile(c,"הגדרות",1,l,t,r,b); return; }
            menuHeader(c,"הגדרות",l,t,r);
            color(0x5539a5de);c.drawRect(l,t+d(63),r,b,p);
            menuRow(c,"1. בהירות: "+brightness+"%",l,t+d(91),r,selected==0,false);
            menuRow(c,"2. שומר מסך: "+(screenSaver?"פעיל":"כבוי"),l,t+d(125),r,selected==1,false);
            menuRow(c,"3. פרטי זיכרון",l,t+d(159),r,selected==2,false);
        }

        private void drawMusic(Canvas c,float l,float t,float r,float b){
            if(page==PAGE_HOME){ drawHomeTile(c,"מוזיקה",2,l,t,r,b); return; }

            if(page==PAGE_MENU){
                menuHeader(c,"מוזיקה",l,t,r);
                color(0x5539a5de);c.drawRect(l,t+d(63),r,b,p);
                String[] m={"1. מנגן לאחרונה","2. כל השירים","3. כרטיס זיכרון","4. אמנים","5. אלבומים","6. סגנונות","7. רשימות השמעה","8. תיקיות"};
                float y=t+d(87);
                for(int i=0;i<m.length;i++){menuRow(c,m[i],l,y,r,selected==i,false);y+=d(31);}
                return;
            }

            if(page==PAGE_LIST){
                menuHeader(c,(musicMode==3?"אמנים":musicMode==4?"אלבומים":"מוזיקה"),l,t,r);
                color(0x5539a5de);c.drawRect(l,t+d(63),r,b,p);
                if(groups.isEmpty()){
                    text(c,"אין נתונים",(l+r)/2,t+d(110),15,Color.WHITE,Paint.Align.CENTER);
                    return;
                }
                int first=Math.max(0,Math.min(selected-6,Math.max(0,groups.size()-9)));
                float y=t+d(87);
                for(int i=first;i<Math.min(groups.size(),first+9);i++){
                    menuRow(c,cut(groups.get(i),30),l,y,r,selected==i,false);
                    y+=d(33);
                }
                return;
            }

            if(page==PAGE_TEXT){
                menuHeader(c,
                    musicMode==0?"מנגן לאחרונה":
                    musicMode==1?"כל השירים":
                    musicMode==2?"כרטיס זיכרון":
                    cut(groupName,24),l,t,r);
                color(0x5539a5de);c.drawRect(l,t+d(63),r,b,p);
                if(visibleTracks.isEmpty()){
                    text(c,"אין שירים",(l+r)/2,t+d(110),15,Color.WHITE,Paint.Align.CENTER);
                    return;
                }
                int first=Math.max(0,Math.min(selected-6,Math.max(0,visibleTracks.size()-9)));
                float y=t+d(87);
                for(int i=first;i<Math.min(visibleTracks.size(),first+9);i++){
                    menuRow(c,cut(visibleTracks.get(i).title,30),l,y,r,selected==i,true);
                    y+=d(33);
                }
            }
        }

        private void drawBt(Canvas c,float l,float t,float r,float b){
            if(page==PAGE_HOME){ drawHomeTile(c,"בלוטוס",3,l,t,r,b); return; }
            menuHeader(c,"בלוטוס",l,t,r);
            color(0x5539a5de);c.drawRect(l,t+d(63),r,b,p);
            boolean on=bluetooth!=null&&bluetooth.isEnabled();
            menuRow(c,"1. Bluetooth: "+(on?"פועל":"כבוי"),l,t+d(91),r,selected==0,false);
            menuRow(c,"2. חיפוש התקנים",l,t+d(125),r,selected==1,false);
            int y=159,i=2;
            for(BluetoothDevice bd:btDevices){
                menuRow(c,(i+1)+". "+cut(MainActivity.this.safeName(bd),24),l,t+d(y),r,selected==i,false);
                y+=33;i++;
            }
        }

        private void drawRecorder(Canvas c,float l,float t,float r,float b){
            if(page==PAGE_HOME){ drawHomeTile(c,recording?"הקלטה":"רשמקול",4,l,t,r,b); return; }
            menuHeader(c,"רשמקול",l,t,r);
            color(0x5539a5de);c.drawRect(l,t+d(63),r,b,p);
            menuRow(c,"1. "+(recording?"עצור הקלטה":"צור הקלטה"),l,t+d(91),r,selected==0,false);
            menuRow(c,"2. נגן הקלטה אחרונה",l,t+d(125),r,selected==1,false);
            if(recording) text(c,"●  מקליט עכשיו",(l+r)/2,t+d(174),14,0xffffb6c0,Paint.Align.CENTER);
        }

        private String formatTime(long ms){ long s=Math.max(0,ms/1000); return String.format(Locale.US,"%d:%02d",s/60,s%60); }

        private void showTopOptions() {
            if(page==PAGE_PLAYER) playerOptions();
            else if(appIndex==0 && page==PAGE_LIST) showFileOptions();
            else if(appIndex==2 && page==PAGE_TEXT && !visibleTracks.isEmpty()) {
                selectedFile = new File(visibleTracks.get(selected).path);
                playerOptions();
            } else if(appIndex==3 && page==PAGE_MENU) {
                openBluetoothSettings();
            } else if(appIndex==4 && page==PAGE_MENU && lastRecording!=null) {
                playPath(lastRecording);
            }
        }

@Override public boolean onTouchEvent(android.view.MotionEvent e) {
            float x=e.getX(), y=e.getY(), w=getWidth(), h=getHeight();
            float cy=Math.min(h-d(190),d(780)), cx=w/2;

            boolean upZone=Math.abs(x-cx)<d(78)&&y>cy-d(155)&&y<cy-d(55);
            boolean downZone=Math.abs(x-cx)<d(78)&&y>cy+d(55)&&y<cy+d(155);

            if(e.getAction()==MotionEvent.ACTION_DOWN){
                pressStarted=System.currentTimeMillis();
                navLongTriggered=false;
                if(page==3 && Math.abs(x-(cx-d(98)))<d(55)){startSeeking(-1);return true;}
                if(page==3 && Math.abs(x-(cx+d(98)))<d(55)){startSeeking(1);return true;}
                if(upZone){startNavRepeat(-1);return true;}
                if(downZone){startNavRepeat(1);return true;}
                return true;
            }

            if(e.getAction()==MotionEvent.ACTION_UP){
                long held=System.currentTimeMillis()-pressStarted;
                stopSeeking();
                stopNavRepeat();

                if(y<d(710)&&x<d(150)){backPressedInside();return true;}
                if(y<d(710)&&x>w-d(150)){showTopOptions();return true;}
                if(Math.abs(x-cx)<d(58)&&Math.abs(y-cy)<d(58)){openSelected();return true;}

                if(upZone){ if(!navLongTriggered) move(-1); return true; }
                if(downZone){ if(!navLongTriggered) move(1); return true; }

                if(Math.abs(y-cy)<d(65)&&x<cx-d(55)){
                    if(page==3) seekBy(held>550?-10000:-5000);
                    else setApp(appIndex-1);
                    return true;
                }
                if(Math.abs(y-cy)<d(65)&&x>cx+d(55)){
                    if(page==3) seekBy(held>550?10000:5000);
                    else setApp(appIndex+1);
                    return true;
                }
                return true;
            }
            return true;
        }

        private void startNavRepeat(final int dir){
            stopNavRepeat();
            navRepeatDir=dir;
            navRepeatRunnable=new Runnable(){
                @Override public void run(){
                    navLongTriggered=true;
                    move(navRepeatDir);
                    handler.postDelayed(this,120);
                }
            };
            handler.postDelayed(navRepeatRunnable,500);
        }

        private void stopNavRepeat(){
            if(navRepeatRunnable!=null) handler.removeCallbacks(navRepeatRunnable);
            navRepeatRunnable=null;
            navRepeatDir=0;
        }

private void move(int d){
            int n=displayCount();
            if(n<=0){selected=0;invalidate();return;}
            selected=(selected+d+n)%n;
            if(page==PAGE_TEXT && appIndex==0 && txtContent.length()>0){
                txtScroll=Math.max(0,Math.min(Math.max(0,txtContent.split("\\n").length*22-300),txtScroll+d*80));
            }
            invalidate();
        }
    }
}