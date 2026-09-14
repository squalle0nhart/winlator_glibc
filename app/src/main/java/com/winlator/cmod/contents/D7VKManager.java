package com.winlator.cmod.contents;

import android.content.Context;
import android.util.Log;

import com.winlator.cmod.XServerDisplayActivity;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.WineInfo;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class D7VKManager {
    public static final String BUNDLED_VERSION = "2.2";
    public static final String BUNDLED_ASSET = "ddrawrapper/d7vk.tzst";
    private static final String CUSTOM_PREFIX = "D7VK-";

    private D7VKManager() {}

    public static boolean isD7VK(String selection) {
        return selection != null && (selection.equalsIgnoreCase("d7vk")
                || selection.regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length()));
    }

    public static List<String> getWrapperEntries(Context context) {
        List<String> entries = new ArrayList<>(Arrays.asList(
                "none", "wined3d", "cnc-ddraw", "dd7to9", "d7vk"));
        ContentsManager manager = new ContentsManager(context);
        manager.syncContents();
        for (ContentProfile profile : manager.getInstalledProfiles(ContentProfile.ContentType.CONTENT_TYPE_D7VK)) {
            entries.add(ContentsManager.getEntryName(profile));
        }
        return entries;
    }

    public static String getWrapperLabel(String entry) {
        if (entry == null) return "";
        if (entry.equalsIgnoreCase("d7vk")) return "D7VK " + BUNDLED_VERSION;
        if (entry.regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length())) {
            String label = entry.substring(CUSTOM_PREFIX.length());
            int lastDash = label.lastIndexOf('-');
            if (lastDash > 0) label = label.substring(0, lastDash);
            return "D7VK " + label;
        }
        return entry;
    }

    public static String getSignature(String selection) {
        return "d7vk".equalsIgnoreCase(selection) ? ";d7vk-bundled=" + BUNDLED_VERSION : "";
    }

    public static boolean isD7VKAssetRequest(String assetFile) {
        if (assetFile == null) return false;
        String normalized = assetFile.replace('\\', '/');
        if (BUNDLED_ASSET.equalsIgnoreCase(normalized)) return true;
        int slash = normalized.lastIndexOf('/');
        String name = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return name.regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length())
                && name.toLowerCase(Locale.ENGLISH).endsWith(".tzst");
    }

    public static String selectionFromAssetRequest(String assetFile) {
        if (assetFile == null) return "";
        String normalized = assetFile.replace('\\', '/');
        if (BUNDLED_ASSET.equalsIgnoreCase(normalized)) return "d7vk";
        int slash = normalized.lastIndexOf('/');
        String file = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return file.toLowerCase(Locale.ENGLISH).endsWith(".tzst")
                ? file.substring(0, file.length() - 5)
                : file;
    }

    public static boolean applyInstalled(Context context, String selection, File destination) {
        if (!isD7VK(selection) || destination == null) return false;

        File bundledDir = null;
        try {
            ContentsManager manager = new ContentsManager(context);
            manager.syncContents();

            WineInfo wineInfo = resolveWineInfo(context, manager);
            if (wineInfo == null || wineInfo.path == null || wineInfo.path.isEmpty()) {
                Log.e("D7VK", "Unable to resolve the selected Wine runtime");
                return false;
            }

            final boolean win64 = wineInfo.isWin64();
            final boolean arm64ec = wineInfo.isArm64EC();
            final File wineDir = new File(wineInfo.path);

            ContentProfile profile;
            File sourceDir;
            if ("d7vk".equalsIgnoreCase(selection)) {
                bundledDir = new File(context.getCacheDir(), "d7vk-bundled");
                if (!extractBundled(context, bundledDir)) return false;

                ContentProfile.ContentFile dll = new ContentProfile.ContentFile();
                dll.source = "syswow64/ddraw.dll";
                dll.target = "${syswow64}/ddraw.dll";
                profile = new ContentProfile();
                profile.type = ContentProfile.ContentType.CONTENT_TYPE_D7VK;
                profile.fileList = Arrays.asList(dll);
                sourceDir = bundledDir;
            } else {
                profile = manager.getProfileByEntryName(selection);
                if (profile == null || profile.remoteUrl != null) return false;
                sourceDir = ContentsManager.getInstallDir(context, profile);
            }

            if (profile.type != ContentProfile.ContentType.CONTENT_TYPE_D7VK
                    || profile.fileList == null || profile.fileList.isEmpty()) {
                return false;
            }

            File canonicalSourceDir = sourceDir.getCanonicalFile();
            List<File[]> copies = new ArrayList<>();

            for (ContentProfile.ContentFile entry : profile.fileList) {
                if (entry == null || entry.source == null || entry.target == null) return false;

                boolean x86 = "${syswow64}/ddraw.dll".equals(entry.target);
                if (!x86 && !"${system32}/ddraw.dll".equals(entry.target)) return false;
                if (!x86 && !win64) continue;

                File source = new File(canonicalSourceDir, entry.source).getCanonicalFile();
                if (!source.toPath().startsWith(canonicalSourceDir.toPath()) || !source.isFile()) return false;

                String architecture = x86
                        ? "i386-windows"
                        : arm64ec ? "aarch64-windows" : "x86_64-windows";
                File builtin = new File(wineDir, "lib/wine/" + architecture + "/ddraw.dll").getCanonicalFile();
                if (!builtin.isFile()) return false;

                File targetDir = new File(destination, x86 && win64 ? "syswow64" : "system32");
                if (!targetDir.isDirectory()) return false;
                copies.add(new File[]{source, builtin, targetDir});
            }

            if (copies.isEmpty()) return false;

            for (File[] copy : copies) {
                File source = copy[0];
                File builtin = copy[1];
                File targetDir = copy[2];
                File proxy = new File(targetDir, "ddraw_.dll");
                File target = new File(targetDir, "ddraw.dll");

                if ((proxy.exists() && !proxy.delete()) || (target.exists() && !target.delete())) return false;
                if (!FileUtils.copy(builtin, proxy) || !proxy.isFile() || proxy.length() != builtin.length()) return false;
                if (!FileUtils.copy(source, target) || !target.isFile() || target.length() != source.length()) return false;
            }

            Log.d("D7VK", "Applied D7VK wrapper: " + getWrapperLabel(selection));
            return true;
        } catch (Exception e) {
            Log.e("D7VK", "Unable to apply " + selection, e);
            return false;
        } finally {
            if (bundledDir != null) FileUtils.delete(bundledDir);
        }
    }

    private static WineInfo resolveWineInfo(Context context, ContentsManager manager) {
        if (!(context instanceof XServerDisplayActivity)) return null;
        Container container = ((XServerDisplayActivity) context).getContainer();
        if (container == null) return null;
        return WineInfo.fromIdentifier(context, manager, container.getWineVersion());
    }

    private static boolean extractBundled(Context context, File destination) {
        FileUtils.delete(destination);
        if (!destination.mkdirs() && !destination.isDirectory()) return false;

        try {
            File canonicalRoot = destination.getCanonicalFile();
            try (InputStream raw = context.getAssets().open(BUNDLED_ASSET);
                 ZstdCompressorInputStream zstd = new ZstdCompressorInputStream(raw);
                 TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
                TarArchiveEntry entry;
                byte[] buffer = new byte[64 * 1024];

                while ((entry = (TarArchiveEntry) tar.getNextEntry()) != null) {
                    if (!tar.canReadEntryData(entry)) continue;
                    File output = new File(destination, entry.getName()).getCanonicalFile();
                    if (!output.toPath().startsWith(canonicalRoot.toPath())) return false;

                    if (entry.isDirectory()) {
                        if (!output.isDirectory() && !output.mkdirs()) return false;
                    } else {
                        if (entry.isSymbolicLink() || entry.isLink()) return false;
                        File parent = output.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return false;
                        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                            int read;
                            while ((read = tar.read(buffer)) != -1) out.write(buffer, 0, read);
                        }
                    }
                }
            }

            return new File(destination, "syswow64/ddraw.dll").isFile();
        } catch (Exception e) {
            Log.e("D7VK", "Unable to extract bundled D7VK " + BUNDLED_VERSION, e);
            return false;
        }
    }
}
