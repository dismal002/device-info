package com.dismal.deviceinfo.probe;

import com.dismal.deviceinfo.R;

import android.bluetooth.BluetoothAdapter;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.ConsumerIrManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.nfc.NfcAdapter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Display;
import android.view.WindowManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Java rewrite of the relevant parts of the original {@code f.java} probe.
 *
 * Covers: Machine, Platform, Chip (SoC), Storage, Memory, Display (size/resolution
 * + touch model), Touch panel, Battery, NFC, Bluetooth, WiFi &amp; Ethernet,
 * Infrared, FM and Camera. Everything else from the original
 * (haptic, fingerprint, sound, individual motion/environment sensors,
 * driver dump) is intentionally left out - add a new Section-returning
 * function the same way if you want one of those back.
 *
 * Same compatibility approach as before: every read is a plain file /
 * getprop / framework-API read with a root-shell fallback where relevant.
 * Nothing here is gated behind a runtime permission it doesn't already
 * declare, so a stock non-rooted phone on any API level from 19 up just
 * shows fewer fields rather than crashing.
 */
public final class DeviceProbe {

    private DeviceProbe() {
    }

    public static final class Section {
        public final String title;
        public final String subtitle;
        public final LinkedHashMap<String, String> details;

        Section(String title, String subtitle, LinkedHashMap<String, String> details) {
            this.title = title;
            this.subtitle = subtitle;
            this.details = details;
        }
    }

    // ---- the same touch/nfc/fm driver vendor identification map as the original ----
    // (component -> comma/semicolon separated driver-name fragments; a
    // leading "~" means "treat as regex" instead of plain substring)
    private static final Map<String, String> DRIVER_VENDOR_MAP = buildDriverVendorMap();

    private static final String[] WELCOME_PACKAGES = {
            "com.example.artificialswitch",
            "com.kst.switchapp",
            "com.android.ext.instruction",
            "com.example.switchbootanim"
    };

    private static final String[] WELCOME_PROPERTIES = {
            "persist.sys.fakeromtypeall", "persist.sys.fakeramtypeall",
            "persist.sys.fakenetworkall", "persist.sys.fakerfrontcameraall",
            "persist.sys.fakerbackcameraall", "persist.sys.fakeandroidvall",
            "persist.sys.fakecpucoreall", "persist.sys.fakemtkcpuall",
            "persist.sys.fakeresolutionall", "persist.sys.fakebatteryall",
            "persist.sys.fakebootlogok", "persist.sys.fakebootlogov",
            "persist.sys.fakeromtype", "persist.sys.fakeramtype",
            "persist.sys.fakenetwork", "persist.sys.fakerfrontcamera",
            "persist.sys.fakerbackcamera", "persist.sys.fakeandroidversion",
            "persist.sys.fakecpucore", "persist.sys.fakemtkcpu",
            "persist.sys.fakemodel", "persist.sys.fakeresolution",
            "persist.sys.fakebattery", "persist.sys.real.rom",
            "persist.sys.ds.fake4g", "persist.sys.cpu.core", "persist.sys.cpu",
            "persist.sys.front.camera.pixel"
    };

    private static final String[] WELCOME_SYSTEM_SETTINGS = {
            "choose_model_value", "key_ramtype", "back_camera_value", "key_cpu_value"
    };

    private static Map<String, String> buildDriverVendorMap() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("touch", "aw9201,fts,ftm4,focal,gt9,gt1x,synaptics,~mxt.ts,nt50359,rmi4,dsx,~mms.ts,nvt,~goodix.ts,sec_ts,sec_touch");
        m.put("nfc", "bcm2079,mt6605,nq-nci,pn5,spi:p61,nfc-548,s3nrn82,pn553");
        m.put("fm", "si47,rtc62");
        // The original also mapped infrared/gps/display/haptic/camera/sensors/
        // etc off this same table - add entries here the same way to bring
        // one of those categories back, the lookup logic doesn't change.
        return m;
    }

    /** Filters {@link #DRIVER_VENDOR_MAP} down to a single component key, mirroring the Kotlin filterKeys call sites. */
    private static Map<String, String> driverVendorMapFor(String component) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        String v = DRIVER_VENDOR_MAP.get(component);
        if (v != null) m.put(component, v);
        return m;
    }

    // ---- the same wireless combo-chip reference table as the original (id -> wifi/bt/gnss gen) ----
    private static final List<String[]> WIRELESS_DATABASE = Arrays.asList(
            new String[]{"hi1101", "huawei", "WiFi4", "BT4.1", "GPS/AGPS/GLONASS/BEIDOU"},
            new String[]{"hi1102a", "huawei", "WiFi5", "BT5.1", "GPS/AGPS/GLONASS/BEIDOU/GALILEO/QZSS"},
            new String[]{"hi1102", "huawei", "WiFi5", "BT4.2", "GPS/AGPS/GLONASS/BEIDOU"},
            new String[]{"hi1103", "huawei", "WiFi5", "BT5.1", "GPS(L1+L5)/AGPS/GLONASS/BEIDOU/GALILEO(E1+E5a)/QZSS(L1+L5)"},
            new String[]{"hi1105", "huawei", "Wifi6", "BT5.1", "GPS(L1+L5)/AGPS/GLONASS/BEIDOU(B1I+B1C+B2a)/GALILEO(E1+E5a)/QZSS(L1+L5)/NavIC"},
            new String[]{"wcn3680", "qualcomm", "WiFi5", "BT4.0", null},
            new String[]{"cherokee", "qualcomm", "WiFi5", "BT5.0", null},
            new String[]{"rome", "qualcomm", "WiFi5", "BT4.1", null},
            new String[]{"hastings", "qualcomm", "WiFi6", "BT5.1", null},
            new String[]{"bcm4359", "broadcom", "WiFi5", "BT4.2", null},
            new String[]{"bcm4375", "broadcom", "WiFi6", "BT5.0", null}
    );

    private static String[] lookupWireless(String hint) {
        String lower = hint.toLowerCase();
        for (String[] row : WIRELESS_DATABASE) {
            if (lower.contains(row[0].toLowerCase())) return row;
        }
        return null;
    }

    // ---- the same SoC reference table as the original (id match -> spec row), ----
    // extended with Rockchip and Google Tensor entries the original never had.
    // columns: 0 match key(s), 1 name, 2 process, 3 modem, 4 cpu, 5 gpu,
    // 6 npu/dsp, 7 ram, 8 storage, 9 wifi, 10 bluetooth, 11 gnss,
    // 12 fast-charge, 13 usb, 14 camera isp
    private static final List<String[]> SOC_DATABASE = Arrays.asList(
            new String[]{"kirin659;hi6250", "kirin659", "16nm", "LTE Cat6", "4xA53@2.4G, 4xA53@1.7G", "Mali-T830 MP2 41GFlops", null, "LPDDR3", "eMMC5.1", null, null, null, null, null, null},
            new String[]{"kirin950;hi3650", "kirin950", "16nm", "LTE Cat6", "4xA72@2.4G, 4xA53@1.8G", "Mali-T880 MP4, 122GFlops", null, "LPDDR4", "eMMC5.1/UFS<2.0", null, null, null, null, null, null},
            new String[]{"kirin960;hi3660", "kirin960", "16nm", "LTE Cat13", "4xA73@2.4G, 4xA53@1.8G", "Mali-G71 MP8@1037M", null, "LPDDR4@1866M", "UFS<2.1", null, null, null, null, null, null},
            new String[]{"kirin710", "kirin710", "12nm", "LTE Cat12", "4xA73@2.2G, 4xA53@1.7G", "Mali-G51 MP4@1000M", null, "LPDDR4@1866M", "eMMC5.1/UFS<2.1", null, null, null, null, null, null},
            new String[]{"kirin970", "kirin970", "10nm", "LTE Cat18", "4xA73@2.36G, 4xA53@1.8G", "Mali-G72 MP12, 347GFlops", "APU", "LPDDR4x <30GB/s", "UFS<2.1", null, null, null, null, null, null},
            new String[]{"kirin980", "kirin980", "7nm", "LTE Cat21", "2xA76@2.6G, 2xA76@1.9G, 4xA55@1.8G", "Mali-G76 MP10, 691GFlops", "1H", "LPDDR4x <34GB/s", "UFS<3.0", null, null, null, null, null, null},
            new String[]{"kirin985", "kirin985", "7nm", "5G/LTE Cat22", "1xA76@2.6G, 3xA76@2.4G, 4xA55@1.8G", "Mali-G77 MP8, 652GFlops", "APU", "LPDDR4x <34GB/s", "UFS<2.1", null, null, null, null, null, null},
            new String[]{"kirin810", "kirin810", "7nm", "LTE Cat12", "2xA76@2.2G, 6xA55@1.9G", "Mali-G52 MP6@820M", "APU", "LPDDR4x <34GB/s", "eMMC5.1/UFS<2.1", null, null, null, null, null, null},
            new String[]{"kirin820", "kirin820", "7nm", "5G/LTE Cat22", "1xA76@2.4G, 3xA76@2.2G, 4xA55@1.8G", "Mali-G57 MP6, 579GFlops", "APU", "LPDDR4x <34GB/s", "UFS<2.1", null, null, null, null, null, null},
            new String[]{"kirin990", "kirin990 5G", "7nm", "5G/LTE Cat21", "2xA76@2.9G, 2xA76@2.4G, 4xA55@2.0G", "Mali-G76 MP16, 896GFlops", "DaVinci", "LPDDR4x <34GB/s", "UFS<3.0", null, null, null, null, null, null},
            new String[]{"kirin9000e", "kirin9000E", "5nm", "5G/LTE", "1xA77@3.1G, 3xA77@2.5G, 4xA55@2.0G", "Mali-G78 MP22", "APU", "LPDDR5 <44GB/s", "UFS<3.1", null, null, null, null, null, null},
            new String[]{"kirin9000", "kirin9000", "5nm", "5G/LTE", "1xA77@3.1G, 3xA77@2.5G, 4xA55@2.0G", "Mali-G78 MP24", "APU", "LPDDR5 <44GB/s", "UFS<3.1", null, null, null, null, null, null},
            new String[]{"sc9863a", "虎贲9863A", "28nm", "LTE Cat7", "4xA55@1.6G, 4xA55@1.2G", "PowerVR GE8322", null, "LPDDR3/LPDDR4x", "eMMC5.1", "Wifi4", "BT4.2", "Beidou,GPS,Glonass", null, null, null},
            new String[]{"ums512", "虎贲T610", "12nm", "LTE Cat7", "2xA75@1.8G, 6xA55@1.8G", "Mali-G52x2", null, "LPDDR3/LPDDR4x", "eMMC5.1", "Wifi5", "BT5.0", "Beidou,GPS,Glonass,Galileo", null, null, null},
            new String[]{"mt6797", "Helio X20", "20nm", "LTE Cat6", "2xA72@2.1G, 4xA53@1.9G, 4xA53@1.4G", "Mali-T880 MP4@780M, 122GFlops", null, "LPDDR3", "eMMC5.1", "Wifi5", "BT4.2", "Beidou,Galileo,Glonass,GPS", null, null, null},
            new String[]{"mt6762", "Helio P22", "12nm", "LTE Cat7", "8xA53@2.0G", "PowerVR GE8320", null, "LPDDR3/LPDDR4x", "eMMC5.1", "Wifi5", "BT5.0", "Beidou,Galileo,Glonass,GPS,QZSS", null, null, null},
            new String[]{"mt6763", "Helio P23", "16nm", "LTE Cat7", "4xA53@2.3G, 4xA53@1.7G", "Mali-G71MP2", null, "LPDDR3/LPDDR4x <15GB/s", "eMMC5.1", "Wifi4", "BT4.2", "Beidou,Galileo,Glonass,GPS,QZSS", null, null, null},
            new String[]{"mt6765", "Helio P35", "12nm", "LTE Cat7", "8xA53@2.2G", "PowerVR GE8320", "P5", "LPDDR4x/LPDDR3", "eMMC5.1", "WiFi5", "BT5.0", "GPS,GLONASS,Beidou,Galileo,QZSS", null, null, null},
            new String[]{"mt6771", "Helio P60", "12nm", "LTE Cat7", "4xA73@2.0G, 4xA53@2.0G", "Mali-G72MP3, 86GFlops", "2xP6 280GMACs", "LPDDR4x/LPDDR3", "eMMC5.1/UFS<2.1", "WiFi5", "BT4.2", "GPS,GLONASS,Beidou,Galileo,QZSS", null, null, null},
            new String[]{"mt6768", "Helio P65", "12nm", "LTE Cat7", "2xA75@2.0G, 6xA55@2.0G", "Mali-G52MC2", "2xP6 280GMACs", "LPDDR4x/LPDDR3", "eMMC5.1", "WiFi5", "BT5.0", "GPS,GLONASS,Beidou,Galileo,QZSS", null, null, null},
            new String[]{"mt6769t", "Helio G80", "12nm", "LTE Cat7", "2xA75@2.0G, 6xA55@1.8G", "Mali-G52MC2", null, "LPDDR4x", "eMMC5.1", "WiFi5", "BT5.0", "Beidou,Galileo,Glonass,GPS", null, null, null},
            new String[]{"mt6769z", "Helio G85", "12nm", "LTE Cat7", "2xA75@2.0G, 6xA55@1.8G", "Mali-G52MC2", null, "LPDDR4x", "eMMC5.1", "WiFi5", "BT5.0", "Beidou,Galileo,Glonass,GPS", null, null, null},
            new String[]{"mt6785", "Helio G90(T)", "12nm", "LTE Cat12", "2xA76@2.0G, 6xA55@2.0G", "Mali-G76MC4", "2xAPU 1.0TMACs", "LPDDR4x/LPDDR3", "eMMC5.1/UFS<2.1", "WiFi5", "BT5.0", "Beidou,Galileo,Glonass,GPS", null, null, null},
            new String[]{"mt6853v", "Dimensity 800U", "7nm", "5G/LTE 2.3Gbps", "2xA76@2.4G, 6xA55@2.0G", "Mali-G57 MC3", "2.4TOPs", "LPDDR4x <17GB/s", "UFS<2.2", "Wifi5", "BT5.1", "Beidou,Galileo,Glonass,GPS,QZSS", null, null, null},
            new String[]{"mt6853", "Dimensity 720", "7nm", "5G/LTE Cat18", "2xA76@2.0G, 6xA55@2.0G", "Mali-G57 MC3", null, "LPDDR4x <17GB/s", "UFS<2.2", "Wifi5", "BT5.1", "Beidou,Galileo,Glonass,GPS,QZSS", null, null, null},
            new String[]{"mt6873", "Dimensity 800", "7nm", "5G/LTE Cat18", "4xA76@2.0G, 4xA55@2.0G", "Mali-G57 MC4", "2.4TOPs", "LPDDR4x <17GB/s", "UFS<2.2", "Wifi5", "BT5.1", "Beidou,Galileo,Glonass,GPS,QZSS", null, null, null},
            new String[]{"mt6875", "Dimensity 820", "7nm", "5G/LTE Cat18", "4xA76@2.6G, 4xA55@2.0G", "Mali-G57 MC5", "2.4TOPs", "LPDDR4x <17GB/s", "UFS<2.2", "Wifi5", "BT5.1", "Beidou,Galileo,Glonass,GPS,QZSS", null, null, null},
            new String[]{"mt6885", "Dimensity 1000L", "7nm", "5G/LTE Cat18", "4xA77@2.2G, 4xA55@2.0G", "Mali-G77 MC9", "APU", "LPDDR4x <30GB/s", "UFS<2.2", "WiFi6", "BT5.1", "GPS L1CA+L5,BeiDou B1I+B2a,Glonass L1OF,Galileo E1+E5a,QZSS L1CA+L5,NavIC", null, null, null},
            new String[]{"mt6889z", "Dimensity 1000+", "7nm", "5G/LTE Cat18", "4xA77@2.2G, 4xA55@2.0G", "Mali-G77 MC9", "4.5TOPs", "LPDDR4x <30GB/s", "UFS<2.2", "WiFi6", "BT5.1", "GPS L1CA+L5,BeiDou B1I+B2a,Glonass L1OF,Galileo E1+E5a,QZSS L1CA+L5,NavIC", null, null, null},
            new String[]{"mt6889", "Dimensity 1000", "7nm", "5G/LTE Cat18", "4xA77@2.6G, 4xA55@2.0G", "Mali-G77 MC9", "4.5TOPs", "LPDDR4x <30GB/s", "UFS<2.2", "WiFi6", "BT5.1", "GPS L1CA+L5,BeiDou B1I+B2a,Glonass L1OF,Galileo E1+E5a,QZSS L1CA+L5,NavIC", null, null, null},
            new String[]{"exynos7420", "Exynos 7420", "14nm", null, "4xA57@2.1G, 4xA53@1.5G", "Mali-T760MP8 210GFlops", null, "LPDDR4", "eMMC5.1/UFS<2.0", null, null, null, null, null, null},
            new String[]{"exynos880", "Exynos 880", "8nm", "5G/LTE Cat18", "2xA77@2.0G, 6xA55@1.8G", "Mali-G76 MP5, 576GFlops", "NPU", "LPDDR4x", "eMMC5.1/UFS<2.1", "Wifi6 ready", "BT5.0", "GPS,GLONASS,BeiDou,Galileo", null, null, null},
            new String[]{"exynos980", "Exynos 980", "8nm", "5G/LTE Cat18", "2xA77@2.2G, 6xA55@1.8G", "Mali-G76 MP5, 576GFlops", "NPU", "LPDDR4x", "eMMC5.1/UFS<2.1", null, null, null, null, null, null},
            new String[]{"exynos990", "Exynos 990", "7nm", "5G/LTE Cat22", "2xM5@2.7G, 2xA76@2.5G, 4xA55@2.0G", "Mali-G77 MP11, 1.2TFlops", "NPU", "LPDDR5 <44GB/s", "UFS<3.0", null, null, null, null, null, null},
            new String[]{"msm8909w", "Snapdragon Wear 2100/2500/3100", "28nm", "LTE Cat4", "4xA7@1.2G", "Adreno 304", null, "LPDDR3", "eMMC 4.5", "Wifi4", "BT4.1", "GPS,GLONASS,Galileo,BeiDou", null, "USB2.0", null},
            new String[]{"sdm429w", "Snapdragon Wear 4100", "12nm", "LTE Cat4", "4xA53@1.7G", "Adreno 504", "Hexagon QDSP6", "LPDDR3", "eMMC 4.5", "Wifi4", "BT5.0/4.2", "GPS,GLONASS,Galileo,BeiDou", null, "USB2.0", null},
            new String[]{"msm8937", "Snapdragon 430", "28nm", "LTE Cat4", "8xA53@1.4G", "Adreno 505, 48GFlops", "Hexagon 536", "LPDDR3", "eMMC5.1", "Wifi5 433Mb/s", "BT4.1", "GPS,GLONASS,Beidou", "QC3", "USB2.0", null},
            new String[]{"msm8940", "Snapdragon 435", "28nm", "LTE Cat7", "8xA53@1.4G", "Adreno 505, 48GFlops", "Hexagon 536", "LPDDR3", "eMMC5.1", "Wifi5 433Mb/s", "BT4.1", "GPS,GLONASS,Beidou,Galileo", "QC3", "USB2.0", null},
            new String[]{"msm8956", "Snapdragon 650", "28nm", "LTE Cat7", "2xA72@1.8G, 4xA53@1.4G", "Adreno 510, 153GFlops", "Hexagon V56", "LPDDR3 <15GB/s", "eMMC5.1", "Wifi5 433Mb/s", "BT4.1", "GPS,GLONASS,Beidou,Galileo", "QC3", "USB2.0", null},
            new String[]{"msm8994", "Snapdragon 810", "20nm", "LTE Cat9", "4xA57@2.0G, 4xA53@1.5G", "Adreno 430", "Hexagon v56", "LPDDR4", "eMMC5.0/UFS<2.0", "Wifi5 867Mb/s", "BT4.1", "Beidou, GLONASS, GPS", "QC3", "USB<3.0", null},
            new String[]{"msm8996pro", "Snapdragon 821", "14nm", "LTE Cat12", "2xKryo@2.4G(max), 2xKryo@1.6G", "Adreno 530, 519GFlops", "Hexagon 680", "LPDDR4 <30GB/s", "eMMC5.1/UFS<2.0", "Wifi5 433Mb/s", "BT4.1", "GPS,GLONASS,Beidou,Galileo,QZSS,SBAS", "QC3", "USB<3.0", null},
            new String[]{"msm8953", "Snapdragon 625", "14nm", "LTE Cat7", "8xA53@2.0G", "Adreno 506, 124GFlops", "Hexagon 546", "LPDDR3 <7.5GB/s", "eMMC5.1", "Wifi5 433Mb/s", "BT4.1", "Beidou,Galileo,GLONASS,GPS", "QC3", "USB<3.0", null},
            new String[]{"sdm450", "Snapdragon 450", "14nm", "LTE Cat7", "8xA53@1.8G", "Adreno 506, 124GFlops", "Hexagon 546", "LPDDR3 <7.5GB/s", "eMMC5.1", "Wifi5 433Mb/s", "BT4.1", "Beidou,Galileo,GLONASS,GPS", "QC3", "USB<3.0", null},
            new String[]{"sdm630", "Snapdragon 630", "14nm", "LTE Cat12", "8xA53@2.2G", "Adreno 508, 163GFlops", "Hexagon 642", "LPDDR3 <7.5GB/s", "eMMC5.1/UFS2.1", "Wifi5 433Mb/s", "BT5.0", "Beidou,Galileo,GLONASS,GPS,QZSS,SBAS", "QC3", "USB<3.1", null},
            new String[]{"sdm632", "Snapdragon 632", "14nm", "LTE Cat7", "4xA73@1.8G, 4xA53@1.8G", "Adreno 506, 124GFlops", "Hexagon 546", "LPDDR3 <7.5GB/s", "eMMC5.1", "Wifi4 364Mb/s", "BT5.0", "Beidou,Galileo,GLONASS,GPS,QZSS,SBAS", "QC3", "USB<3.1", null},
            new String[]{"sdm636", "Snapdragon 636", "14nm", "LTE Cat12", "4xA73@2.2G, 4xA53@1.8G", "Adreno 509", "Hexagon 680", "LPDDR4 <11GB/s", "eMMC5.1/UFS<2.1", "Wifi5 433Mb/s", "BT5.0", "Beidou,Galileo,GLONASS,GPS,QZSS,SBAS", "QC4", "USB<3.1", null},
            new String[]{"msm8996", "Snapdragon 820", "14nm", "LTE Cat12", "2xKryo@2.1G, 6xKryo@1.6G", "Adreno 530, 498GFlops", "Hexagon 680", "LPDDR4 <30GB/s", "eMMC5.1/UFS<2.0", "Wifi5 867Mb/s", "BT4.1", "Beidou,Galileo,GLONASS,GPS", "QC3", "USB<3.0", "Spectra"},
            new String[]{"sdm439", "Snapdragon 439", "12nm", "LTE Cat5", "4xA53@2.0G, 4xA53@1.5G", "Adreno 505, 48GFlops", "Hexagon 536", "LPDDR3 <6.4GB/s", "eMMC5.1", "wifi5 433Mb/s", "BT5.0", "Beidou,Galileo,GLONASS,GPS", "QC3", "USB2.0", null},
            new String[]{"sdm660", "Snapdragon 660", "14nm", "LTE Cat12", "4xA73@2.2G, 4xA53@1.8G", "Adreno 512, 217GFlops", "Hexagon 680", "LPDDR4/4x <15GB/s", "emmc5.1/UFS<2.1", "wifi5 867Mb/s", "BT5.0", "Beidou,Galileo,GLONASS,GPS,QZSS,SBAS", "QC4", "USB<3.1", null},
            new String[]{"sm6150", "Snapdragon 675", "11nm", "LTE Cat12", "2xA76@2.0G, 6xA55@1.7G", "Adreno 612", "Hexagon 685", "LPDDR4x <15GB/s", "eMMC5.1/UFS<2.1", "wifi5 867Mb/s", "BT5.0", "Beidou,Galileo,GLONASS,GPS,QZSS,SBAS", "QC4+", "USB<3.1/USB-C", null},
            new String[]{"sdm670", "Snapdragon 670", "10nm", "LTE Cat12", "2xA75@2.0G, 6xA55@1.7G", "Adreno 615, 350GFlops", "Hexagon 685", "LPDDR4x <15GB/s", "eMMC5.1/UFS<2.1", "wifi5 867Mb/s", "BT5.0", "Beidou,Galileo,GLONASS,GPS,QZSS,SBAS", "QC4+", "USB<3.1/USB-C", null},
            new String[]{"sdm710", "Snapdragon 710", "10nm", "LTE Cat15", "2xA75@2.2G, 6xA55@1.7G", "Adreno 616, 352GFlops", "Hexagon 685", "LPDDR4x <15GB/s", "eMMC5.1/UFS<2.1", "Wifi5 867Mb/s", "BT5.0", "Beidou,Galileo,GLONASS,GPS,QZSS,SBAS", "QC4", "USB<3.1/USB-C", null},
            new String[]{"sdm712", "Snapdragon 712", "10nm", "LTE Cat15", "2xA75@2.3G, 6xA55@1.7G", "Adreno 616, 384GFlops", "Hexagon 685", "LPDDR4x <15GB/s", "UFS<2.1", "Wifi5", "BT5.0", "Beidou,Galileo,GLONASS,GPS,QZSS,SBAS", "QC4+", "USB<3.1/USB-C", null},
            new String[]{"sm7150;magpie", "Snapdragon 730", "8nm", "LTE Cat15", "2xA76@2.2G, 6xA55@1.8G", "Adreno 618, 386GFlops", "Hexagon 688", "LPDDR4x <15GB/s", "UFS<3.0", "Wifi6 ready", "BT5.0", "Beidou,Galileo,GLONASS,GPS,QZSS,SBAS", "QC4+", "USB<3.1/USB-C", null},
            new String[]{"msm8998", "Snapdragon 835", "10nm", "LTE Cat16", "4xA73@2.5G, 4xA53@1.9G", "Adreno 540, 558GFlops", "Hexagon 682", "LPDDR4x <30GB/s", "UFS<2.1", "Wifi5 867Mb/s", "BT5.0", "Beidou,Galileo,GLONASS,GPS,QZSS,SBAS", "QC4", "USB<3.1", "Spectra 180"},
            new String[]{"sdm845", "Snapdragon 845", "10nm", "LTE Cat18", "4xA75@2.8G, 4xA55@1.8G", "Adreno 630, 727GFlops", "Hexagon 685,3TOPs", "LPDDR4x <30GB/s", "UFS<2.1", "Wifi5 867Mb/s", "BT5.0", "Beidou,Galileo,GLONASS,GPS,QZSS,SBAS", "QC4+", "USB<3.1", "Spectra 280"},
            new String[]{"sm7225", "Snapdragon 750G", "8nm", "5G/LTE Cat18", "2xA77@2.2G, 6xA55@1.8G", "Adreno 619", "Hexagon 694", "LPDDR4x <17GB/s ", "UFS<3.0", "WiFi6 ready", "BT5.1", "Beidou,Galileo,GLONASS,Dual frequency GNSS,NavIC,GPS,GNSS,QZSS,SBAS", "QC4+", "USB<3.1/USB-C", null},
            new String[]{"sm7250-ac", "Snapdragon 768G", "7nm", "5G/LTE Cat18", "1xA76@2.8G, 1xA76@2.4G, 6xA55@1.8G", "Adreno 620, 700GFlops", "Hexagon 696, 5.4TOPs", "LPDDR4x <17GB/s", "eMMC5.1/UFS<3.0", "WiFi6 ready", "BT5.2", "Beidou,Galileo,GLONASS,Dual frequency GNSS,NavIC,GPS,GNSS,QZSS,SBAS", "QC4+", "USB<3.1/USB-C", null},
            new String[]{"sm7250;sdm765", "Snapdragon 765(G)", "7nm", "5G/LTE Cat18", "1xA76@2.4G, 1xA76@2.2G, 6xA55@1.8G", "Adreno 620, 700GFlops", "Hexagon 696, 5.4TOPs", "LPDDR4x <17GB/s", "eMMC5.1/UFS<3.0", "WiFi6 ready", "BT5.0", "Beidou,Galileo,GLONASS,Dual frequency GNSS,NavIC,GPS,GNSS,QZSS,SBAS", "QC4+", "USB<3.1/USB-C", null},
            new String[]{"sm8150-ac;sm8150_plus;sdm855plus", "Snapdragon 855 Plus", "7nm", "Int:LTE Cat20, Ext:5G", "1xA76@3.0G, 3xA76@2.4G, 4xA55@1.8G", "Adreno 640, 1TFlops", "Hexagon 690,7TOPs", "LPDDR4x <34GB/s", "UFS<3.0", "WiFi6 ready/Ext:802.11ad/ay 10Gbps", "Bluetooth 5.0", "Beidou,Galileo,GLONASS,Dual frequency GNSS,GPS,QZSS,SBAS", "QC4+", "USB<3.1/USB-C", "Spectra 380"},
            new String[]{"sm8150", "Snapdragon 855", "7nm", "Int:LTE Cat20, Ext:5G", "1xA76@2.8G, 3xA76@2.4G, 4xA55@1.8G", "Adreno 640, 950GFlops", "Hexagon 690,7TOPs", "LPDDR4x <34GB/s", "UFS<3.0", "WiFi6 ready/Ext:802.11ad/ay 10Gbps", "Bluetooth 5.0", "Beidou,Galileo,GLONASS,Dual frequency GNSS,GPS,QZSS,SBAS", "QC4+", "USB<3.1/USB-C", "Spectra 380"},
            new String[]{"sm8250-ab;sm8250_plus", "Snapdragon 865 Plus", "7nm", "Ext:5G/LTE Cat24", "1xA77@3.1G, 3xA77@2.4G, 4xA55@1.8G", "Adreno 650, 1.4TFlops", "Hexagon 698,15TOPs", "LPDDR4X/LPDDR5 <44GB/s", "UFS<3.1", "WiFi6E 3.6Gbps/Ext:802.11ad/ay 10Gbps", "Bluetooth 5.2", "Beidou,Galileo,GLONASS,Dual frequency GNSS,NavIC,GPS,GNSS,QZSS,SBAS", "QC5", "USB<3.1/USB-C", "Spectra 480"},
            new String[]{"sm8250;kona", "Snapdragon 865", "7nm", "Ext:5G/LTE Cat24", "1xA77@2.8G, 3xA77@2.4G, 4xA55@1.8G", "Adreno 650, 1.3TFlops", "Hexagon 698,15TOPs", "LPDDR4x/LPDDR5 <44GB/s", "UFS<3.1", "WiFi6 1.8Gbps/Ext:802.11ad/ay 10Gbps", "Bluetooth 5.1", "Beidou,Galileo,GLONASS,Dual frequency GNSS,NavIC,GPS,GNSS,QZSS,SBAS", "QC5", "USB<3.1/USB-C", "Spectra 480"},
            new String[]{"sm8350", "Snapdragon 888", "5nm", "5G/LTE Cat24", "1xX1@2.8G, 3xA78@2.4G, 4xA55@1.8G", "Adreno 660", "Hexagon 780,26TOPs", "LPDDR5 <50GB/s", "UFS<3.1", "WiFi6e 3.6Gbps", "Bluetooth 5.2", "Beidou,Galileo,GLONASS,Dual frequency GNSS,NavIC,GPS,GNSS,QZSS,SBAS", "QC5", "USB<3.1/USB-C", "Spectra 580"},
            new String[]{"rk3288", "RK3288", "28nm", null, "4xA17@1.8G", "Mali-T764 MP4", null, "DDR3/LPDDR3", "eMMC4.5/5.1", null, "BT4.0", null, null, "USB2.0/3.0", null},
            new String[]{"rk3328", "RK3328", "28nm", null, "4xA53@1.5G", "Mali-450 MP2", null, "DDR3/DDR4/LPDDR3", "eMMC5.1", null, "BT4.2", null, null, "USB2.0/3.0", null},
            new String[]{"rk3399", "RK3399", "28nm", null, "2xA72@2.0G, 4xA53@1.5G", "Mali-T860 MP4", null, "DDR3/LPDDR3/LPDDR4", "eMMC5.1/UFS2.0", "Wifi5", "BT4.1/5.0", null, null, "USB<3.0", null},
            new String[]{"rk3399pro", "RK3399Pro", "28nm", null, "2xA72@1.8G, 4xA53@1.4G", "Mali-T864", "NPU 2.4TOPs", "LPDDR3/LPDDR4", "eMMC5.1/UFS2.0", "Wifi5", "BT4.1/5.0", null, null, "USB<3.0", null},
            new String[]{"rk3326;px30", "RK3326/PX30", "22nm", null, "4xA35@1.5G", "Mali-G31 MP2", null, "DDR3/DDR4/LPDDR3", "eMMC5.1", null, "BT4.2", null, null, "USB2.0", null},
            new String[]{"rk3566", "RK3566", "22nm", null, "4xA55@1.8G", "Mali-G52 1EE", "NPU 0.8TOPs", "DDR3/DDR4/LPDDR4/LPDDR4x", "eMMC5.1/UFS2.1", "Wifi5/6", "BT5.0", null, null, "USB<3.0", null},
            new String[]{"rk3568", "RK3568", "22nm", null, "4xA55@2.0G", "Mali-G52 2EE", "NPU 0.8TOPs", "DDR3/DDR4/LPDDR4/LPDDR4x", "eMMC5.1/UFS2.1", "Wifi5/6", "BT5.0", null, null, "USB<3.0", null},
            new String[]{"rk3588", "RK3588", "8nm", null, "4xA76@2.4G, 4xA55@1.8G", "Mali-G610 MP4", "NPU 6TOPs", "LPDDR4/LPDDR4x/LPDDR5", "eMMC5.1/UFS3.1", "Wifi6", "BT5.0", null, null, "USB<3.2", null},
            new String[]{"rk3562", "RK3562", "22nm", null, "4xA53@2.0G", "Mali-G52 1EE", "NPU 1.0TOPs", "DDR3/DDR4/LPDDR4/LPDDR4x", "eMMC5.1/UFS2.1", "Wifi5", "BT5.0", null, null, "USB<3.0", null},
            new String[]{"rk3128", "RK3128", "28nm", null, "4xA7@1.3G", "Mali-400 MP2", null, "DDR3/LPDDR2", "eMMC4.5/5.0", null, "BT4.0", null, null, "USB2.0", null},
            new String[]{"rk3126", "RK3126", "28nm", null, "4xA7@1.3G", "Mali-400 MP2", null, "DDR3/LPDDR2/LPDDR3", "eMMC4.5/5.1", null, "BT4.0", null, null, "USB2.0", null},
            new String[]{"rk3308", "RK3308", "28nm", null, "4xA35@1.3G", null, null, "DDR3/DDR3L/LPDDR2/LPDDR3", "eMMC5.1", null, "BT4.2/5.0", null, null, "USB2.0", null},
            new String[]{"rk3128h", "RK3128H", "28nm", null, "4xA7@1.3G", "Mali-400 MP2", null, "DDR3/LPDDR2", "eMMC4.5/5.0", null, "BT4.0", null, null, "USB2.0", null},
            new String[]{"gs101", "Google Tensor", "5nm", "5G/LTE", "2xX1@2.8G, 2xA76@2.25G, 4xA55@1.8G", "Mali-G78 MP20", "TPU + Edge TPU", "LPDDR5 <44GB/s", "UFS3.1", "Wifi6", "BT5.2", "GPS,GLONASS,Galileo,BeiDou,QZSS", null, "USB<3.1/USB-C", "Titan M2"},
            new String[]{"gs201", "Google Tensor G2", "5nm", "5G/LTE", "2xX1@2.85G, 2xA78@2.35G, 4xA55@1.8G", "Mali-G710 MP7", "TPU G2 + Edge TPU", "LPDDR5 <51GB/s", "UFS3.1", "Wifi6e", "BT5.2", "GPS,GLONASS,Galileo,BeiDou,QZSS", null, "USB<3.1/USB-C", "Titan M2"},
            new String[]{"zuma;zuma_pro;gs301", "Google Tensor G3", "4nm", "5G/LTE", "1xX3@2.91G, 4xA715@2.37G, 4xA510@1.7G", "Mali-G715 MP7", "TPU G3 + Edge TPU", "LPDDR5x <60GB/s", "UFS3.1", "Wifi6e", "BT5.3", "GPS,GLONASS,Galileo,BeiDou,QZSS", null, "USB<3.2/USB-C", "Titan M2"},
            new String[]{"zumapro", "Google Tensor G4", "4nm", "5G/LTE", "1xX4@3.1G, 3xA720@2.6G, 4xA520@1.95G", "Mali-G715 MP7", "TPU G4 + Edge TPU", "LPDDR5x", "UFS3.1", "Wifi7", "BT5.3", "GPS,GLONASS,Galileo,BeiDou,QZSS", null, "USB<3.2/USB-C", "Titan M2"}
    );

    private static String[] lookupSoc(String hint) {
        if (hint == null) return null;
        String lower = hint.toLowerCase();
        for (String[] row : SOC_DATABASE) {
            if (row[0] == null) continue;
            for (String key : row[0].split("[;,]")) {
                if (lower.contains(key)) return row;
            }
        }
        return null;
    }

    /**
     * Generic fallback for chips not in {@link #SOC_DATABASE} (new/rare models): a
     * vendor + family guess purely from the id's shape. This is what makes
     * "any" Mediatek/Qualcomm/Rockchip/Tensor chip identify at least by
     * vendor and rough family even the day it launches, before anyone has
     * added a curated row for it.
     */
    private static String[] guessVendor(String hint) {
        String h = hint.toLowerCase();
        if (Pattern.compile("^mt6[7-9][0-9]{2}").matcher(h).find() || h.contains("dimensity")) {
            return new String[]{"MediaTek", "Dimensity"};
        }
        if (Pattern.compile("^mt6[0-6][0-9]{2}").matcher(h).find() || h.contains("helio")) {
            return new String[]{"MediaTek", "Helio"};
        }
        if (Pattern.compile("^mt[68][0-9]{3}").matcher(h).find()) {
            return new String[]{"MediaTek", "MediaTek"};
        }
        if (Pattern.compile("^(sm|sdm|msm|qcm|qcs|apq)[0-9]{3,4}").matcher(h).find()) {
            return new String[]{"Qualcomm", "Snapdragon"};
        }
        if (h.contains("snapdragon")) {
            return new String[]{"Qualcomm", "Snapdragon"};
        }
        if (Pattern.compile("^rk[0-9]{3,4}").matcher(h).find()) {
            return new String[]{"Rockchip", "Rockchip"};
        }
        if (Pattern.compile("^(gs[0-9]{3}|zuma)").matcher(h).find() || h.contains("tensor")) {
            return new String[]{"Google", "Tensor"};
        }
        if (Pattern.compile("^(exynos|s5e)[0-9]{3,4}").matcher(h).find()) {
            return new String[]{"Samsung", "Exynos"};
        }
        if (Pattern.compile("^(kirin|hi[36][0-9]{3})").matcher(h).find()) {
            return new String[]{"HiSilicon", "Kirin"};
        }
        if (Pattern.compile("^(sc[0-9]{4}|ums[0-9]{3,4})").matcher(h).find()) {
            return new String[]{"Unisoc", "Unisoc"};
        }
        if (Pattern.compile("^(a[0-9]{2}|apple)").matcher(h).find() && h.contains("apple")) {
            return new String[]{"Apple", "Apple Silicon"};
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    public static List<Section> run(Context context, ShellExec shell) {
        Map<String, String> props = readProps(shell);
        Map<String, String> hwinfo = readHwInfo(shell);
        String cpuInfo = shell.readFile("/proc/cpuinfo");
        String cpuHardware = resolveSocHint(context, props, cpuInfo);

        List<String> i2cNames = new ArrayList<>();
        String i2cListing = shell.listDir("/sys/bus/i2c/devices/");
        if (i2cListing != null) {
            Pattern i2cEntry = Pattern.compile("[0-9]+-[0-9a-fA-F]+");
            for (String entry : i2cListing.split("\n")) {
                if (i2cEntry.matcher(entry).matches()) {
                    String name = shell.readFile("/sys/bus/i2c/devices/" + entry + "/name");
                    if (name != null) i2cNames.add(name);
                }
            }
        }

        List<Section> sections = new ArrayList<>();
        sections.add(welcomeDeviceCheck(context, props));
        sections.add(machine(context, props));
        sections.add(platform(context, props));
        sections.add(chipInfo(context, props, cpuHardware, cpuInfo));
        sections.add(storageInfo(context, shell, props, hwinfo));
        sections.add(memoryInfo(context, shell, props, hwinfo, cpuHardware));
        sections.add(display(context, shell, props, i2cNames));
        sections.add(touchPanel(context, shell, props, hwinfo, i2cNames));
        sections.add(battery(context));
        sections.add(nfc(context, shell, i2cNames));
        sections.add(bluetooth(context, props, cpuHardware));
        sections.add(wifiAndEthernet(context, props, cpuHardware));
        sections.add(lte(context, props, cpuHardware));
        sections.add(infrared(context));
        sections.add(fm(context, shell, props, i2cNames));
        sections.add(camera(context));
        sections.add(rootCheck(context, props));
        return sections;
    }

    // ------------------------------------------------------------------
    // Welcome Device Check
    // ------------------------------------------------------------------

    private static Section welcomeDeviceCheck(Context context, Map<String, String> props) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        boolean found = false;
        PackageManager pm = context.getPackageManager();

        for (String packageName : WELCOME_PACKAGES) {
            try {
                pm.getPackageInfo(packageName, 0);
                d.put(context.getString(R.string.probe_app_found_label, packageName), context.getString(R.string.value_installed));
                found = true;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Not installed (or not visible on this Android version).
            }
        }

        for (String property : WELCOME_PROPERTIES) {
            String value = props.get(property);
            if (value != null) {
                d.put(context.getString(R.string.probe_system_property_label, property), value);
                found = true;
            }
        }

        for (String setting : WELCOME_SYSTEM_SETTINGS) {
            try {
                String value = Settings.System.getString(context.getContentResolver(), setting);
                if (value != null) {
                    d.put(context.getString(R.string.probe_system_setting_label, setting), value);
                    found = true;
                }
            } catch (Exception ignored) {
                // A provider error should not prevent the other checks.
            }
        }

        String result = found ? context.getString(R.string.probe_welcome_check_result_yes) : context.getString(R.string.probe_welcome_check_result_no);
        d.put(context.getString(R.string.probe_result), result);
        return new Section(context.getString(R.string.probe_welcome_device_check), result, d);
    }

    // ------------------------------------------------------------------
    // Root
    // ------------------------------------------------------------------

    public static boolean hasRootAccess() {
        String[] suPaths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
                "/su/bin/su", "/system/bin/.ext/su", "/system/usr/we-need-root/su"
        };
        for (String path : suPaths) {
            if (new File(path).isFile()) {
                return true;
            }
        }
        return false;
    }

    private static Section rootCheck(Context context, Map<String, String> props) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        boolean rootIndicatorFound = false;

        if (hasRootAccess()) {
            d.put(context.getString(R.string.probe_su_binary_label, "su"), context.getString(R.string.value_found));
            rootIndicatorFound = true;
        }

        String roSecure = props.get("ro.secure");
        d.put("ro.secure", roSecure != null ? roSecure : context.getString(R.string.value_not_set));
        if ("0".equals(roSecure)) {
            d.put(context.getString(R.string.probe_root_indicator_ro_secure), context.getString(R.string.probe_root_indicator_ro_secure_value));
            rootIndicatorFound = true;
        }

        String roDebuggable = props.get("ro.debuggable");
        d.put("ro.debuggable", roDebuggable != null ? roDebuggable : context.getString(R.string.value_not_set));
        if ("1".equals(roDebuggable)) {
            d.put(context.getString(R.string.probe_root_indicator_ro_debuggable), context.getString(R.string.probe_root_indicator_ro_debuggable_value));
            rootIndicatorFound = true;
        }

        String buildType = props.get("ro.build.type");
        d.put(context.getString(R.string.probe_build_type), buildType != null ? buildType : context.getString(R.string.value_not_set));
        if ("eng".equalsIgnoreCase(buildType)) {
            d.put(context.getString(R.string.probe_root_indicator_build_type), context.getString(R.string.probe_engineering_build));
            rootIndicatorFound = true;
        }

        String buildTags = props.get("ro.build.tags");
        if (buildTags != null) d.put(context.getString(R.string.probe_build_tags), buildTags);
        if (buildTags != null && buildTags.contains("test-keys")) {
            d.put(context.getString(R.string.probe_root_indicator_build_tags), context.getString(R.string.probe_test_keys));
            rootIndicatorFound = true;
        }

        String result = rootIndicatorFound
                ? context.getString(R.string.probe_root_indicators_detected)
                : context.getString(R.string.probe_no_root_indicators_detected);
        d.put(context.getString(R.string.probe_result), result);
        return new Section(context.getString(R.string.probe_root), result, d);
    }

    // ------------------------------------------------------------------
    // 1) Machine (phone model)
    // ------------------------------------------------------------------

    private static Section machine(Context context, Map<String, String> props) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();

        String manufacturer = Build.MANUFACTURER;
        String brand = Build.BRAND;
        String product = Build.PRODUCT;
        String model = Build.MODEL;
        d.put(context.getString(R.string.probe_manufacturer), manufacturer);
        if (brand == null || !brand.equalsIgnoreCase(manufacturer)) d.put(context.getString(R.string.probe_brand), brand);
        d.put(context.getString(R.string.probe_product), product);
        d.put(context.getString(R.string.probe_model), model);

        String marketingName = StrUtil.firstNonBlank(
                props.get("ro.config.marketing_name"),
                props.get("ro.product.marketname"),
                props.get("ro.vivo.market.name"),
                props.get("ro.oppo.market.name"),
                props.get("ro.product.display"),
                props.get("ro.semc.product.name"),
                props.get("ro.product.nickname"),
                props.get("persist.sys.exif.model")
        );
        if (marketingName != null) d.put(context.getString(R.string.probe_marketing_name), marketingName);

        String btName = null;
        try {
            btName = Settings.Secure.getString(context.getContentResolver(), "bluetooth_name");
        } catch (Exception ignored) {
        }
        if (btName != null && !btName.trim().isEmpty()) d.put(context.getString(R.string.probe_device_name), btName);

        String subtitle = marketingName != null ? marketingName : model;
        return new Section(context.getString(R.string.probe_machine), subtitle, d);
    }

    // ------------------------------------------------------------------
    // 2) Platform (build info)
    // ------------------------------------------------------------------

    private static Section platform(Context context, Map<String, String> props) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        d.put(context.getString(R.string.probe_android_version), Build.VERSION.RELEASE);
        d.put(context.getString(R.string.probe_sdk_level), String.valueOf(Build.VERSION.SDK_INT));
        d.put(context.getString(R.string.probe_build_id), Build.ID);
        d.put(context.getString(R.string.probe_build_display), Build.DISPLAY);
        d.put(context.getString(R.string.probe_fingerprint), Build.FINGERPRINT);
        d.put(context.getString(R.string.probe_build_type), Build.TYPE);

        String emui = props.get("ro.build.version.emui");
        String miui = props.get("ro.miui.ui.version.name");
        String sense = props.get("ro.build.sense.version");
        String coloros = props.get("ro.build.version.opporom");
        String funtouch = props.get("ro.vivo.os.version");
        String aosip = props.get("ro.aosip.version");
        String zui = props.get("ro.com.zui.version");

        String romVersion = StrUtil.firstNonBlank(
                emui != null ? "EMUI " + emui : null,
                miui != null ? "MIUI " + miui : null,
                sense != null ? "Sense " + sense : null,
                coloros != null ? "ColorOS " + coloros : null,
                funtouch != null ? "Funtouch " + funtouch : null,
                aosip != null ? "AOSiP " + aosip : null,
                zui != null ? "ZUI " + zui : null,
                props.get("ro.rom.version"),
                props.get("ro.smartisan.version")
        );
        if (romVersion != null) d.put(context.getString(R.string.probe_custom_rom), romVersion);

        return new Section(context.getString(R.string.probe_platform), romVersion != null ? romVersion : "Android " + Build.VERSION.RELEASE, d);
    }

    // ------------------------------------------------------------------
    // 3) Chip / SoC
    // ------------------------------------------------------------------

    /** Uses the original probe's property fallbacks when /proc/cpuinfo hides Hardware. */
    private static String resolveSocHint(android.content.Context context, Map<String, String> props, String cpuInfo) {
        String zukHint = props.get("ro.config.zuk.cpuinfo");
        if (zukHint != null && zukHint.toLowerCase().contains("865 plus")) return "sm8250_plus";
        String gfxDriver = props.get("ro.gfx.driver.0");
        if (gfxDriver != null && gfxDriver.toLowerCase().contains("sdm870")) return "sm8250_ac";

        String hardware = firstFieldValue(cpuInfo, context.getString(R.string.probe_hardware));
        List<String> candidates = new ArrayList<>();
        addIfNotNull(candidates, hardware);
        addIfNotNull(candidates, props.get("ro.soc.model"));
        addIfNotNull(candidates, props.get("ro.hardware.version_id"));
        addIfNotNull(candidates, props.get("ro.meizu.hardware.soc"));
        addIfNotNull(candidates, props.get("ro.hardware.chipname"));
        addIfNotNull(candidates, props.get("ro.arch"));
        addIfNotNull(candidates, props.get("ro.board.platform"));
        addIfNotNull(candidates, Build.HARDWARE);
        addIfNotNull(candidates, Build.BOARD);
        addIfNotNull(candidates, props.get("ro.hardware"));

        // Prefer a candidate that maps to the curated SoC table. Kernels often
        // expose only a generic value such as "qcom" in /proc/cpuinfo.
        for (String c : candidates) {
            if (lookupSoc(c) != null) return c;
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static void addIfNotNull(List<String> list, String value) {
        if (value != null) list.add(value);
    }

    /** Finds the value of a "Field: value" line (case-insensitive field name) in a /proc-style text blob. */
    private static String firstFieldValue(String text, String field) {
        if (text == null) return null;
        for (String line : text.split("\n")) {
            int idx = line.indexOf(':');
            if (idx < 0) continue;
            String key = line.substring(0, idx).trim();
            if (key.equalsIgnoreCase(field)) {
                String value = line.substring(idx + 1).trim();
                return value;
            }
        }
        return null;
    }

    private static Section chipInfo(Context context, Map<String, String> props, String cpuHardware, String cpuInfo) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();

        String hint = StrUtil.firstNonBlank(
                cpuHardware,
                Build.HARDWARE,
                Build.BOARD,
                props.get("ro.board.platform"),
                props.get("ro.hardware")
        );
        if (hint == null) hint = context.getString(R.string.unknown);
        d.put(context.getString(R.string.probe_hardware_id), hint);

        String processor = firstFieldValue(cpuInfo, context.getString(R.string.probe_processor));
        if (processor != null && !processor.trim().isEmpty()) d.put(context.getString(R.string.probe_cpu_reported_by_kernel), processor);
        String cpuArch = firstFieldValue(cpuInfo, context.getString(R.string.probe_cpu_architecture));
        if (cpuArch != null && !cpuArch.trim().isEmpty()) d.put(context.getString(R.string.probe_cpu_architecture), "ARMv" + cpuArch);

        String abi;
        if (Build.VERSION.SDK_INT >= 21) {
            abi = String.join(", ", Build.SUPPORTED_ABIS);
        } else {
            abi = Build.CPU_ABI;
        }
        d.put(context.getString(R.string.probe_abi), abi);
        d.put(context.getString(R.string.probe_cores), String.valueOf(Runtime.getRuntime().availableProcessors()));

        String[] row = lookupSoc(hint);
        String subtitle;
        if (row != null) {
            subtitle = row[1] != null ? row[1] : hint;
            putIfPresent(d, context.getString(R.string.probe_process_node), row[2]);
            putIfPresent(d, context.getString(R.string.probe_modem), row[3]);
            putIfPresent(d, context.getString(R.string.probe_cpu), row[4]);
            putIfPresent(d, context.getString(R.string.probe_gpu), row[5]);
            putIfPresent(d, context.getString(R.string.probe_npu_dsp), row[6]);
            putIfPresent(d, context.getString(R.string.probe_ram_type), row[7]);
            putIfPresent(d, context.getString(R.string.probe_storage_interface), row[8]);
            putIfPresent(d, context.getString(R.string.probe_wifi), row[9]);
            putIfPresent(d, context.getString(R.string.probe_bluetooth_lower), row[10]);
            putIfPresent(d, context.getString(R.string.probe_gnss), row[11]);
            putIfPresent(d, context.getString(R.string.probe_fast_charging), row[12]);
            putIfPresent(d, context.getString(R.string.probe_usb), row[13]);
            putIfPresent(d, context.getString(R.string.probe_camera_isp), row[14]);
        } else {
            String[] guess = guessVendor(hint);
            if (guess != null) {
                subtitle = guess[0] + " " + guess[1] + " (" + hint + ")";
                d.put(context.getString(R.string.probe_vendor), guess[0]);
                d.put(context.getString(R.string.probe_family), guess[1]);
            } else {
                subtitle = hint;
            }
        }

        return new Section(context.getString(R.string.probe_chip_soc), subtitle, d);
    }

    private static void putIfPresent(Map<String, String> d, String key, String value) {
        if (value != null) d.put(key, value);
    }

    // ------------------------------------------------------------------
    // 4) Storage - eMMC / UFS model
    // ------------------------------------------------------------------

    private static final Map<String, String> EMMC_VENDOR_BY_MANFID = buildEmmcVendorMap();

    private static Map<String, String> buildEmmcVendorMap() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("2", "Sandisk");
        m.put("11", "Toshiba");
        m.put("13", "Micron");
        m.put("15", "Samsung");
        m.put("45", "Sandisk");
        m.put("70", "Kingston");
        m.put("74", "Transcend");
        m.put("90", "Hynix");
        m.put("fe", "Micron");
        return m;
    }

    private static Section storageInfo(
            Context context,
            ShellExec shell,
            Map<String, String> props,
            Map<String, String> hwinfo
    ) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        String capacityForSubtitle = null;

        try {
            StatFs statFs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long totalMb = (statFs.getBlockCountLong() * statFs.getBlockSizeLong()) / 1024 / 1024;
            long sizeGb = 1L;
            while (sizeGb < totalMb / 1024 && sizeGb < 1_048_576L) sizeGb *= 2;
            capacityForSubtitle = sizeGb + "G";
            d.put(context.getString(R.string.probe_capacity), capacityForSubtitle);
        } catch (Exception ignored) {
        }

        String emmcModel = null;
        String ufsModelForSubtitle = null;
        String storageModelForSubtitle = null;
        for (String base : new String[]{
                "/sys/class/mmc_host/mmc0/mmc0:0001/",
                "/sys/class/mmc_host/emmc/emmc:0001/"
        }) {
            String manfidRaw = shell.readFile(base + "manfid");
            String name = shell.readFile(base + "name");
            if (manfidRaw != null || name != null) {
                String manfid = manfidRaw != null
                        ? manfidRaw.replaceAll("0x0+", "").toLowerCase()
                        : null;
                String vendor = manfid != null && EMMC_VENDOR_BY_MANFID.containsKey(manfid)
                        ? EMMC_VENDOR_BY_MANFID.get(manfid)
                        : manfid;
                emmcModel = StrUtil.joinSkipBlanks(" ", vendor, name);
                if (emmcModel != null) break;
            }
        }
        if (emmcModel != null) d.put(context.getString(R.string.probe_emmc), emmcModel);

        String scsi = shell.readFile("/proc/scsi/scsi");
        String ufsLine = null;
        if (scsi != null) {
            for (String line : scsi.split("\n")) {
                if (line.contains("Vendor:") && line.contains("Model:")) {
                    ufsLine = line;
                    break;
                }
            }
        }
        if (ufsLine != null) {
            String vendor = StrUtil.findGroup(ufsLine, "Vendor:\\s*(\\S+)");
            String modelField = StrUtil.findGroup(ufsLine, "Model:\\s*(\\S+)");
            String ufsModel = StrUtil.joinSkipBlanks(" ", vendor, modelField);
            if (ufsModel != null) {
                d.put(context.getString(R.string.probe_ufs), ufsModel);
                ufsModelForSubtitle = ufsModel;
            }
        }

        // Modern UFS usually presents its logical unit as sda. These files are
        // readable on many devices even when /proc/scsi is SELinux-restricted.
        String sdaVendor = shell.readFile("/sys/block/sda/device/vendor");
        String sdaModel = shell.readFile("/sys/block/sda/device/model");
        String sdaRevision = shell.readFile("/sys/block/sda/device/rev");
        if ((sdaVendor != null && !sdaVendor.trim().isEmpty()) || (sdaModel != null && !sdaModel.trim().isEmpty())) {
            String ufsModel = StrUtil.joinSkipBlanks(" ", sdaVendor, sdaModel);
            if (ufsModel != null && d.putIfAbsent(context.getString(R.string.probe_ufs), ufsModel) == null && ufsModelForSubtitle == null) {
                ufsModelForSubtitle = ufsModel;
            }
            if (sdaRevision != null && !sdaRevision.trim().isEmpty()) d.put(context.getString(R.string.probe_ufs_revision), sdaRevision);
        }

        if (ufsModelForSubtitle == null && emmcModel == null) {
            if (sdaModel != null) {
                d.put(context.getString(R.string.probe_storage_model), sdaModel);
                storageModelForSubtitle = sdaModel;
            }
        }

        String bootDevice = StrUtil.firstNonBlank(props.get("ro.boot.bootdevice"), props.get("ro.boot.boot_devices"));
        if (bootDevice != null) d.put(context.getString(R.string.probe_boot_device), bootDevice);

        String mountOut = shell.run("mount");
        if (mountOut != null) {
            String dataLine = null;
            for (String line : mountOut.split("\n")) {
                if (line.contains(" /data ") || line.contains(" on /data ")) dataLine = line;
            }
            if (dataLine != null) {
                String[] fields = dataLine.trim().split("\\s+");
                String fs = fields.length > 4 ? fields[4] : (fields.length > 2 ? fields[2] : null);
                if (fs != null) d.put(context.getString(R.string.probe_data_filesystem), fs);
            }
        }

        String ufsHealth = shell.readFile("/sys/kernel/debug/ufshcd0/dump_health_desc");
        if (ufsHealth != null) {
            String flat = ufsHealth.replace('\n', ',');
            if (!flat.trim().isEmpty()) d.put(context.getString(R.string.probe_ufs_health), flat);
        }
        putIfPresent(d, context.getString(R.string.probe_ufs_hint_vendor), props.get("ro.meizu.hardware.ufs"));
        putIfPresent(d, context.getString(R.string.probe_ufs_hint_bootloader), props.get("ro.boot.hardware.ufs"));
        putIfPresent(d, context.getString(R.string.probe_storage_manufacturer_hint), props.get("ro.meizu.storage.manufacturer"));
        if (hwinfo.get("ufs") != null) d.put(context.getString(R.string.probe_hwinfo_ufs), hwinfo.get("ufs"));
        if (hwinfo.get("emmc") != null) d.put(context.getString(R.string.probe_hwinfo_emmc), hwinfo.get("emmc"));

        String subtitle = StrUtil.firstNonBlank(ufsModelForSubtitle, emmcModel, storageModelForSubtitle, capacityForSubtitle);
        return new Section(context.getString(R.string.probe_storage), subtitle, d);
    }

    // ------------------------------------------------------------------
    // 5) Memory - capacity, RAM vendor, and DDR generation
    // ------------------------------------------------------------------

    private static Section memoryInfo(
            Context context,
            ShellExec shell,
            Map<String, String> props,
            Map<String, String> hwinfo,
            String cpuHardware
    ) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();

        long totalBytes = 0L;
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            if (activityManager != null) {
                activityManager.getMemoryInfo(memoryInfo);
                totalBytes = memoryInfo.totalMem;
            }
        } catch (Exception ignored) {
        }
        if (totalBytes <= 0) {
            String memInfo = shell.readFile("/proc/meminfo");
            String memTotalKb = StrUtil.findGroup(memInfo, "(?im)^MemTotal:\\s*(\\d+)");
            try {
                if (memTotalKb != null) totalBytes = Long.parseLong(memTotalKb) * 1024L;
            } catch (NumberFormatException ignored) {
            }
        }
        if (totalBytes > 0) d.put(context.getString(R.string.probe_capacity), roundedRamCapacity(totalBytes));

        // These are the same public/vendor hints consulted by f.java. The
        // kernel paths vary by chipset, so collect all readable ones rather
        // than assuming a single manufacturer-specific location.
        String ddr = props.get("ro.boot.hardware.ddr");
        String dramInfo = props.get("ro.boot.dram_info");
        String ddrInfo = firstReadable(shell,
                "/proc/ddr_info",
                "/proc/dram_info",
                "/proc/draminfo",
                "/sys/kernel/debug/ddr_info");
        String hwDdr = StrUtil.firstNonBlank(hwinfo.get("ddr"), hwinfo.get("ddr_vendor"));

        putIfPresent(d, context.getString(R.string.probe_ddr_hint), ddr);
        putIfPresent(d, context.getString(R.string.probe_dram_info), dramInfo);
        putIfPresent(d, context.getString(R.string.probe_ddr_info), ddrInfo);
        putIfPresent(d, context.getString(R.string.probe_hardware_info), hwDdr);

        String socRamType = null;
        String[] soc = lookupSoc(cpuHardware);
        if (soc != null) socRamType = soc[7];
        String ramEvidence = StrUtil.joinSkipBlanks(" ", ddr, dramInfo, ddrInfo, hwDdr);
        String ramType = detectRamType(ramEvidence);
        if (ramType == null) ramType = socRamType;
        putIfPresent(d, context.getString(R.string.probe_ram_type), ramType);

        String manufacturer = detectRamManufacturer(ramEvidence);
        putIfPresent(d, context.getString(R.string.probe_manufacturer), manufacturer);

        String capacityForSubtitle = totalBytes > 0 ? roundedRamCapacity(totalBytes) : null;
        String subtitle = StrUtil.joinSkipBlanks(" · ", capacityForSubtitle, manufacturer, ramType);
        return new Section(context.getString(R.string.probe_memory), subtitle, d);
    }

    private static String firstReadable(ShellExec shell, String... paths) {
        for (String path : paths) {
            String value = shell.readFile(path);
            if (!StrUtil.isBlank(value)) return value;
        }
        return null;
    }

    private static String roundedRamCapacity(long bytes) {
        long mib = bytes / 1024L / 1024L;
        if (mib <= 0) return null;
        long[] commonMib = {256, 512, 768, 1024, 2048, 3072, 4096, 6144, 8192, 10240, 12288, 16384, 18432};
        for (long capacity : commonMib) {
            if (mib <= capacity) return capacity >= 1024 ? (capacity / 1024) + " GB" : capacity + " MB";
        }
        return ((mib + 1023) / 1024) + " GB";
    }

    private static String detectRamType(String evidence) {
        if (evidence == null) return null;
        String upper = evidence.toUpperCase();
        String[] types = {"LPDDR5X", "LPDDR5T", "LPDDR5", "LPDDR4X", "LPDDR4", "LPDDR3", "LPDDR2", "DDR5", "DDR4", "DDR3L", "DDR3", "DDR2"};
        for (String type : types) {
            if (upper.contains(type)) return type;
        }
        return null;
    }

    private static String detectRamManufacturer(String evidence) {
        if (evidence == null) return null;
        String lower = evidence.toLowerCase();
        if (lower.contains("samsung")) return "Samsung";
        if (lower.contains("sk hynix") || lower.contains("hynix")) return "SK hynix";
        if (lower.contains("micron")) return "Micron";
        if (lower.contains("elpida")) return "Elpida";
        if (lower.contains("nanya")) return "Nanya";
        if (lower.contains("winbond")) return "Winbond";
        if (lower.contains("kingston")) return "Kingston";
        return null;
    }

    // ------------------------------------------------------------------
    // 6) Display - size / resolution (+ touch model summary)
    // ------------------------------------------------------------------

    private static Section display(
            Context context,
            ShellExec shell,
            Map<String, String> props,
            List<String> i2cNames
    ) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        String resolutionForSubtitle = null;
        String physicalSizeForSubtitle = null;

        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        int widthPx = 0;
        int heightPx = 0;
        Float refreshHz = null;
        try {
            Display disp = wm != null ? wm.getDefaultDisplay() : null;
            if (disp != null) {
                disp.getRealMetrics(dm);
                Point p = new Point();
                disp.getRealSize(p);
                widthPx = p.x;
                heightPx = p.y;
                refreshHz = disp.getRefreshRate();
            }
        } catch (Exception ignored) {
        }
        if (widthPx > 0 && heightPx > 0) {
            resolutionForSubtitle = Math.max(widthPx, heightPx) + " x " + Math.min(widthPx, heightPx);
            d.put(context.getString(R.string.probe_resolution), resolutionForSubtitle);
        }
        d.put(context.getString(R.string.probe_density), dm.densityDpi + " dpi (" + resDensityBucket(dm.densityDpi) + ")");
        if (dm.xdpi > 0 && dm.ydpi > 0 && widthPx > 0 && heightPx > 0) {
            double wIn = widthPx / dm.xdpi;
            double hIn = heightPx / dm.ydpi;
            double diag = Math.sqrt(wIn * wIn + hIn * hIn);
            physicalSizeForSubtitle = String.format(java.util.Locale.US, "%.2f in", diag);
            d.put(context.getString(R.string.probe_physical_size), physicalSizeForSubtitle);
        }
        if (refreshHz != null && refreshHz > 0) {
            d.put(context.getString(R.string.probe_refresh_rate), ((int) (float) refreshHz) + " Hz");
        }

        if (Build.VERSION.SDK_INT >= 24) {
            try {
                Display disp = wm != null ? wm.getDefaultDisplay() : null;
                Boolean hdr = disp != null ? disp.isHdr() : null;
                if (Boolean.TRUE.equals(hdr)) d.put(context.getString(R.string.probe_hdr), context.getString(R.string.value_supported));
            } catch (Exception ignored) {
            }
        }

        String strA = props.get("sys.panel.display");
        if (strA != null && !strA.equalsIgnoreCase("unknown")) d.put(context.getString(R.string.probe_panel), strA);
        putIfPresent(d, context.getString(R.string.probe_panel_vendor), props.get("ro.boot.mi.panel_vendor"));
        putIfPresent(d, context.getString(R.string.probe_panel_type), props.get("persist.vivo.phone.panel_type"));

        StringBuilder joined = new StringBuilder();
        for (String n : i2cNames) {
            if (joined.length() > 0) joined.append(",");
            joined.append(n);
        }
        String touchVendor = StrUtil.matchVendor(joined.toString().toLowerCase(), TOUCH_VENDOR_MAP);
        if (touchVendor != null) d.put(context.getString(R.string.probe_touch_model), touchVendor);

        String subtitle = StrUtil.firstNonBlank(resolutionForSubtitle, physicalSizeForSubtitle);
        return new Section(context.getString(R.string.probe_display), subtitle, d);
    }

    private static String resDensityBucket(int dpi) {
        if (dpi <= 120) return "ldpi";
        if (dpi <= 160) return "mdpi";
        if (dpi <= 213) return "tvdpi";
        if (dpi <= 240) return "hdpi";
        if (dpi <= 320) return "xhdpi";
        if (dpi <= 480) return "xxhdpi";
        return "xxxhdpi";
    }

    // ------------------------------------------------------------------
    // 6) Touch panel
    // ------------------------------------------------------------------

    private static final Map<String, String> TOUCH_VENDOR_MAP = buildTouchVendorMap();

    private static Map<String, String> buildTouchVendorMap() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("goodix", "goodix,gt9,gt1x,gtx8");
        m.put("synaptics", "synaptics,dsx");
        m.put("novatek", "novatek,nt50359,~nvt");
        m.put("himax", "himax");
        m.put("focaltech", "fts,ftm4,focal,st_ts");
        m.put("microchip (atmel)", "~mxt.ts");
        m.put("melfas", "~mms.ts");
        m.put("parade", "parade");
        m.put("elan", "elan");
        m.put("samsung", "sec_ts,sec_touch");
        return m;
    }

    private static Section touchPanel(
            Context context,
            ShellExec shell,
            Map<String, String> props,
            Map<String, String> hwinfo,
            List<String> i2cNames
    ) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();

        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) {
            PackageManager pm = context.getPackageManager();
            String multiTouch;
            if (pm.hasSystemFeature("android.hardware.touchscreen.multitouch.jazzhand")) {
                multiTouch = "5+";
            } else if (pm.hasSystemFeature("android.hardware.touchscreen.multitouch.distinct")) {
                multiTouch = "2+";
            } else if (pm.hasSystemFeature("android.hardware.touchscreen.multitouch")) {
                multiTouch = "2";
            } else {
                multiTouch = "1";
            }
            d.put(context.getString(R.string.probe_multi_touch), multiTouch);
        }

        String driverHit = null;
        Map<String, String> touchDriverMap = driverVendorMapFor("touch");
        for (String name : i2cNames) {
            if (StrUtil.matchVendor(name, touchDriverMap) != null) {
                driverHit = name;
                break;
            }
        }
        if (driverHit != null) d.put(context.getString(R.string.probe_driver_node), driverHit);

        for (String path : new String[]{
                "/proc/hw_info/tp_info",
                "/proc/driver/tp_info",
                "/sys/class/wind_device/device_info/ctp_info",
                "/sys/touchscreen/touch_chip_info",
                "/sys/devices/platform/huawei_touch/touch_chip_info"
        }) {
            String value = shell.readFile(path);
            if (value != null && !value.trim().isEmpty()) {
                String key = path.substring(path.lastIndexOf('/') + 1);
                d.put(key, value.replace("\n", ", "));
            }
        }
        if (shell.readFile("/proc/gt9xx_config") != null) d.put(context.getString(R.string.probe_goodix_gt9xx), context.getString(R.string.value_present));
        if (shell.readFile("/proc/gt1x_debug") != null) d.put(context.getString(R.string.probe_goodix_gt1x), context.getString(R.string.value_present));
        if (shell.readFile("/proc/AEON_TPD") != null) d.put(context.getString(R.string.probe_aeon_tpd), context.getString(R.string.value_present));

        for (String key : new String[]{"touch ic", "ctp", "touch_screen", "touchpanel", "tp maker"}) {
            String v = hwinfo.get(key);
            if (v != null) d.put(context.getString(R.string.probe_hwinfo_label, key), v);
        }

        StringBuilder combined = new StringBuilder();
        for (String v : d.values()) combined.append(v).append(",");
        combined.append(",");
        for (String n : i2cNames) combined.append(n).append(",");
        String vendor = StrUtil.matchVendor(combined.toString().toLowerCase(), TOUCH_VENDOR_MAP);

        String subtitle = vendor != null ? vendor : (d.values().isEmpty() ? null : d.values().iterator().next());
        return new Section(context.getString(R.string.probe_touch_panel), subtitle, d);
    }

    // ------------------------------------------------------------------
    // 7) Battery
    // ------------------------------------------------------------------

    private static Section battery(Context context) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        String levelForSubtitle = null;
        String designCapacityForSubtitle = null;
        Intent intent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent != null) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                levelForSubtitle = (level * 100 / scale) + "%";
                d.put(context.getString(R.string.probe_level), levelForSubtitle);
            }

            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            String statusStr;
            if (status == BatteryManager.BATTERY_STATUS_CHARGING) statusStr = context.getString(R.string.probe_battery_status_charging);
            else if (status == BatteryManager.BATTERY_STATUS_DISCHARGING) statusStr = context.getString(R.string.probe_battery_status_discharging);
            else if (status == BatteryManager.BATTERY_STATUS_FULL) statusStr = context.getString(R.string.probe_battery_status_full);
            else if (status == BatteryManager.BATTERY_STATUS_NOT_CHARGING) statusStr = context.getString(R.string.probe_battery_status_not_charging);
            else statusStr = context.getString(R.string.unknown);
            d.put(context.getString(R.string.probe_status), statusStr);

            int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
            if (plugged > 0) {
                String pluggedStr;
                if (plugged == BatteryManager.BATTERY_PLUGGED_AC) pluggedStr = context.getString(R.string.probe_battery_plugged_ac);
                else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) pluggedStr = context.getString(R.string.probe_battery_plugged_usb);
                else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) pluggedStr = context.getString(R.string.probe_battery_plugged_wireless);
                else pluggedStr = context.getString(R.string.yes);
                d.put(context.getString(R.string.probe_plugged), pluggedStr);
            }

            int health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
            String healthStr;
            if (health == BatteryManager.BATTERY_HEALTH_GOOD) healthStr = context.getString(R.string.probe_battery_health_good);
            else if (health == BatteryManager.BATTERY_HEALTH_OVERHEAT) healthStr = context.getString(R.string.probe_battery_health_overheat);
            else if (health == BatteryManager.BATTERY_HEALTH_DEAD) healthStr = context.getString(R.string.probe_battery_health_dead);
            else if (health == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE) healthStr = context.getString(R.string.probe_battery_health_over_voltage);
            else if (health == BatteryManager.BATTERY_HEALTH_COLD) healthStr = context.getString(R.string.probe_battery_health_cold);
            else healthStr = context.getString(R.string.unknown);
            d.put(context.getString(R.string.probe_health), healthStr);

            String technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (technology != null) d.put(context.getString(R.string.probe_technology), technology);

            int tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            if (tempTenths != Integer.MIN_VALUE) {
                d.put(context.getString(R.string.probe_temperature), String.format(java.util.Locale.US, "%.1f C", tempTenths / 10.0));
            }

            int voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE);
            if (voltageMv != Integer.MIN_VALUE) d.put(context.getString(R.string.probe_voltage), voltageMv + " mV");
        }

        // Design capacity: hidden PowerProfile API, best-effort, not always
        // reachable depending on hidden-API policy for the running app.
        try {
            Class<?> cls = Class.forName("com.android.internal.os.PowerProfile");
            java.lang.reflect.Constructor<?> ctor = cls.getConstructor(Context.class);
            Object instance = ctor.newInstance(context);
            Object capObj = cls.getMethod("getBatteryCapacity").invoke(instance);
            double cap = capObj instanceof Double ? (Double) capObj : 0;
            if (cap > 0) {
                designCapacityForSubtitle = Math.round(cap) + " mAh";
                d.put(context.getString(R.string.probe_design_capacity), designCapacityForSubtitle);
            }
        } catch (Exception ignored) {
        }

        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                if (bm != null) {
                    int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    if (cur != Integer.MIN_VALUE) d.put(context.getString(R.string.probe_current_now), cur + " uA");
                }
            } catch (Exception ignored) {
            }
        }

        String subtitle = StrUtil.firstNonBlank(designCapacityForSubtitle, levelForSubtitle);
        return new Section(context.getString(R.string.probe_battery), subtitle, d);
    }

    // ------------------------------------------------------------------
    // 8) NFC
    // ------------------------------------------------------------------

    private static Section nfc(Context context, ShellExec shell, List<String> i2cNames) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        boolean hasFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC);
        d.put(context.getString(R.string.probe_supported), hasFeature ? context.getString(R.string.yes) : context.getString(R.string.no));

        String chipTypeForSubtitle = null;
        String driverHitForSubtitle = null;
        String enabledForSubtitle = null;

        if (hasFeature) {
            try {
                NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
                enabledForSubtitle = adapter != null && adapter.isEnabled() ? context.getString(R.string.yes) : context.getString(R.string.no);
                d.put(context.getString(R.string.probe_enabled), enabledForSubtitle);
            } catch (Exception ignored) {
            }
            String chipType = shell.readFile("/sys/nfc/nfc_chip_type");
            if (chipType != null) {
                d.put(context.getString(R.string.probe_chip_type), chipType);
                chipTypeForSubtitle = chipType;
            }
            String fwListing = shell.listDir("/vendor/firmware/nfc/");
            if (fwListing != null && !fwListing.trim().isEmpty()) d.put(context.getString(R.string.probe_firmware_present), context.getString(R.string.yes));

            Map<String, String> nfcDriverMap = driverVendorMapFor("nfc");
            String hit = null;
            for (String name : i2cNames) {
                if (StrUtil.matchVendor(name, nfcDriverMap) != null) {
                    hit = name;
                    break;
                }
            }
            if (hit != null) {
                d.put(context.getString(R.string.probe_driver_node), hit);
                driverHitForSubtitle = hit;
            }
        }

        String subtitle = !hasFeature ? context.getString(R.string.value_not_supported)
                : StrUtil.firstNonBlank(chipTypeForSubtitle, driverHitForSubtitle, enabledForSubtitle);
        return new Section(context.getString(R.string.probe_nfc), subtitle, d);
    }

    // ------------------------------------------------------------------
    // 9) Bluetooth
    // ------------------------------------------------------------------

    private static Section bluetooth(Context context, Map<String, String> props, String cpuHardware) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        boolean hasFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
        d.put(context.getString(R.string.probe_supported), hasFeature ? context.getString(R.string.yes) : context.getString(R.string.no));

        String btNameForSubtitle = null;
        String chipsetVersionForSubtitle = null;

        if (hasFeature) {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter != null) {
                    d.put(context.getString(R.string.probe_enabled), adapter.isEnabled() ? context.getString(R.string.yes) : context.getString(R.string.no));
                    // Address is redacted to a constant (02:00:00:00:00:00) since
                    // API 23 for privacy unless the app holds BLUETOOTH_CONNECT -
                    // that's a platform restriction, not something to work around.
                    try {
                        String name = adapter.getName();
                        d.put(context.getString(R.string.probe_name), name != null ? name : "");
                        btNameForSubtitle = name;
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            boolean le = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
            d.put(context.getString(R.string.probe_low_energy), le ? context.getString(R.string.yes) : context.getString(R.string.no));

            String hint = StrUtil.firstNonBlank(cpuHardware, Build.HARDWARE, props.get("ro.board.platform"));
            if (hint == null) hint = "";
            String[] row = lookupWireless(hint);
            if (row != null) {
                if (row[3] != null) {
                    d.put(context.getString(R.string.probe_chipset_bt_version), row[3]);
                    chipsetVersionForSubtitle = row[3];
                }
                d.put(context.getString(R.string.probe_combo_chip_vendor), row[1] != null ? row[1] : "");
            }
        }

        String subtitle = !hasFeature ? context.getString(R.string.value_not_supported)
                : StrUtil.firstNonBlank(chipsetVersionForSubtitle, btNameForSubtitle);
        return new Section(context.getString(R.string.probe_bluetooth), subtitle, d);
    }

    // ------------------------------------------------------------------
    // 10) WiFi & Ethernet
    // ------------------------------------------------------------------

    private static Section wifiAndEthernet(Context context, Map<String, String> props, String cpuHardware) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        String wifiStandardForSubtitle = null;

        boolean hasWifi = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI);
        d.put(context.getString(R.string.probe_wifi_supported), hasWifi ? context.getString(R.string.yes) : context.getString(R.string.no));
        if (hasWifi) {
            d.put(context.getString(R.string.probe_wifi_direct), context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) ? context.getString(R.string.yes) : context.getString(R.string.no));
            if (Build.VERSION.SDK_INT >= 30) {
                d.put(context.getString(R.string.probe_6_ghz_band), context.getPackageManager().hasSystemFeature("android.hardware.wifi.6ghz.support") ? context.getString(R.string.yes) : context.getString(R.string.unknown));
            }
            String hint = StrUtil.firstNonBlank(cpuHardware, Build.HARDWARE, props.get("ro.board.platform"));
            if (hint == null) hint = "";
            String[] row = lookupWireless(hint);
            if (row != null) {
                if (row[2] != null) {
                    d.put(context.getString(R.string.probe_chipset_wifi_standard), row[2]);
                    wifiStandardForSubtitle = row[2];
                }
                if (row[1] != null) d.put(context.getString(R.string.probe_combo_chip_vendor), row[1]);
            }
        }

        boolean hasEthernet = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_ETHERNET);
        d.put(context.getString(R.string.probe_ethernet_supported), hasEthernet ? context.getString(R.string.yes) : context.getString(R.string.no));
        try {
            String[] allIfaces = new File("/sys/class/net").list();
            List<String> ifaces = new ArrayList<>();
            if (allIfaces != null) {
                for (String n : allIfaces) {
                    if (n.startsWith("eth")) ifaces.add(n);
                }
            }
            if (!ifaces.isEmpty()) {
                d.put(context.getString(R.string.probe_ethernet_interfaces), String.join(", ", ifaces));
                String iface = ifaces.get(0);
                File stateFile = new File("/sys/class/net/" + iface + "/operstate");
                if (stateFile.canRead()) {
                    String state = readFileText(stateFile).trim();
                    d.put(context.getString(R.string.probe_iface_state_label, iface), state);
                }
            }
        } catch (Exception ignored) {
        }

        String subtitle = StrUtil.firstNonBlank(wifiStandardForSubtitle, hasWifi ? context.getString(R.string.value_supported) : null);
        return new Section(context.getString(R.string.probe_wifi__ethernet), subtitle, d);
    }

    // ------------------------------------------------------------------
    // x) LTE (Baseband/Cellular)
    // ------------------------------------------------------------------

    private static Section lte(Context context, Map<String, String> props, String cpuHardware) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();

        boolean hasFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
        if (!hasFeature) {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                try {
                    android.net.NetworkInfo info = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
                    hasFeature = info != null;
                } catch (Exception ignored) {
                }
            }
        }
        
        if (hasFeature) {
            String hint = StrUtil.firstNonBlank(cpuHardware, Build.HARDWARE, props.get("ro.board.platform"));
            if (hint == null) hint = "";
            String[] soc = lookupSoc(hint);
            String name = soc != null && soc.length > 1 ? soc[1] : null;
            String modem = soc != null && soc.length > 3 ? soc[3] : null;
            String baseband = StrUtil.joinSkipBlanks(", ", name, modem);
            putIfPresent(d, context.getString(R.string.probe_baseband_lowercase), baseband);
            
            String operatorName = props.get("gsm.operator.alpha");
            String networkType = props.get("gsm.network.type");
            
            try {
                android.telephony.TelephonyManager tm = (android.telephony.TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    String tmOperator = tm.getNetworkOperatorName();
                    if (tmOperator != null && !tmOperator.trim().isEmpty()) {
                        operatorName = tmOperator;
                    }
                    int tmNetType = tm.getNetworkType();
                    if (tmNetType != android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN) {
                        networkType = String.valueOf(tmNetType);
                    }
                }
            } catch (Exception ignored) {
            }

            putIfPresent(d, context.getString(R.string.probe_operator_name), operatorName);
            putIfPresent(d, context.getString(R.string.probe_network_type), networkType);
        }

        String subtitle = hasFeature ? context.getString(R.string.value_supported) : context.getString(R.string.value_not_supported);
        return new Section(context.getString(R.string.probe_lte), subtitle, d);
    }

    private static String readFileText(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(f)))) {
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 11) Infrared
    // ------------------------------------------------------------------

    private static Section infrared(Context context) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        boolean hasFeature = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR);
        d.put(context.getString(R.string.probe_supported), hasFeature ? context.getString(R.string.yes) : context.getString(R.string.no));

        if (hasFeature && Build.VERSION.SDK_INT >= 19) {
            try {
                ConsumerIrManager irManager = (ConsumerIrManager) context.getSystemService(Context.CONSUMER_IR_SERVICE);
                if (irManager != null && irManager.hasIrEmitter()) {
                    ConsumerIrManager.CarrierFrequencyRange[] freqs = irManager.getCarrierFrequencies();
                    if (freqs != null && freqs.length > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < freqs.length; i++) {
                            if (i > 0) sb.append("; ");
                            sb.append(freqs[i].getMinFrequency()).append("-").append(freqs[i].getMaxFrequency());
                        }
                        d.put(context.getString(R.string.probe_carrier_frequency_ranges_hz), sb.toString());
                    }
                }
            } catch (Exception ignored) {
            }
        }

        String subtitle = !hasFeature ? context.getString(R.string.value_not_supported) : context.getString(R.string.value_supported);
        return new Section(context.getString(R.string.probe_infrared), subtitle, d);
    }

    // ------------------------------------------------------------------
    // 12) FM radio
    // ------------------------------------------------------------------

    private static Section fm(
            Context context,
            ShellExec shell,
            Map<String, String> props,
            List<String> i2cNames
    ) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        boolean hasFeature = props.get("ro.hardware.fm") != null || props.get("ro.fm.transmitter") != null;
        String supportedForSubtitle = hasFeature ? context.getString(R.string.yes) : context.getString(R.string.unknown);
        d.put(context.getString(R.string.probe_supported), supportedForSubtitle);
        String fmHardware = props.get("ro.hardware.fm");
        putIfPresent(d, context.getString(R.string.probe_fm_hardware), fmHardware);
        putIfPresent(d, context.getString(R.string.probe_fm_transmitter), props.get("ro.fm.transmitter"));

        Map<String, String> fmDriverMap = driverVendorMapFor("fm");
        String hit = null;
        for (String name : i2cNames) {
            if (StrUtil.matchVendor(name, fmDriverMap) != null) {
                hit = name;
                break;
            }
        }
        if (hit != null) d.put(context.getString(R.string.probe_driver_node), hit);

        String subtitle = StrUtil.firstNonBlank(fmHardware, hit, supportedForSubtitle);
        return new Section(context.getString(R.string.probe_fm_radio), subtitle, d);
    }

    // ------------------------------------------------------------------
    // 13) Camera
    // ------------------------------------------------------------------

    private static Section camera(Context context) {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        PackageManager pm = context.getPackageManager();
        boolean hasAny = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        d.put(context.getString(R.string.probe_supported), hasAny ? context.getString(R.string.yes) : context.getString(R.string.no));
        if (!hasAny) return new Section(context.getString(R.string.probe_camera), context.getString(R.string.value_not_supported), d);

        d.put(context.getString(R.string.probe_flash), pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) ? context.getString(R.string.yes) : context.getString(R.string.no));

        int count = 0;
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                String[] ids = manager != null ? manager.getCameraIdList() : new String[0];
                count = ids.length;
                for (String id : ids) {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                    Integer facingVal = chars.get(CameraCharacteristics.LENS_FACING);
                    String facing;
                    if (facingVal != null && facingVal == CameraCharacteristics.LENS_FACING_FRONT) facing = context.getString(R.string.probe_camera_facing_front);
                    else if (facingVal != null && facingVal == CameraCharacteristics.LENS_FACING_BACK) facing = context.getString(R.string.probe_camera_facing_back);
                    else if (facingVal != null && facingVal == CameraCharacteristics.LENS_FACING_EXTERNAL) facing = context.getString(R.string.probe_camera_facing_external);
                    else facing = context.getString(R.string.unknown);

                    Size pixelArraySize = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                    String mp = pixelArraySize != null
                            ? String.format(java.util.Locale.US, "%.1f MP",
                                    pixelArraySize.getWidth() * pixelArraySize.getHeight() / 1_000_000.0)
                            : null;

                    float[] focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    String focal = null;
                    if (focalLengths != null && focalLengths.length > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < focalLengths.length; i++) {
                            if (i > 0) sb.append(",");
                            sb.append(focalLengths[i]).append("mm");
                        }
                        focal = sb.toString();
                    }

                    Integer level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    String combined = StrUtil.joinSkipBlanks(", ", mp, focal, level != null ? String.valueOf(level) : null);
                    d.put(context.getString(R.string.probe_camera_row_label, id, facing), combined != null ? combined : facing);
                }
            } catch (Exception ignored) {
            }
        } else {
            try {
                count = android.hardware.Camera.getNumberOfCameras();
                for (int i = 0; i < count; i++) {
                    android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
                    android.hardware.Camera.getCameraInfo(i, info);
                    String facing = info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT
                            ? context.getString(R.string.probe_camera_facing_front)
                            : context.getString(R.string.probe_camera_facing_back);
                    d.put(context.getString(R.string.probe_camera_row_label, String.valueOf(i), facing), facing);
                }
            } catch (Exception ignored) {
            }
        }
        d.put(context.getString(R.string.probe_camera_count), String.valueOf(count));

        String subtitle = context.getString(R.string.probe_camera_count_subtitle, count);
        return new Section(context.getString(R.string.probe_camera), subtitle, d);
    }

    // ------------------------------------------------------------------
    // Shared readers
    // ------------------------------------------------------------------

    private static Map<String, String> readProps(ShellExec shell) {
        return StrUtil.parseGetprop(shell.run("getprop"));
    }

    private static Map<String, String> readHwInfo(ShellExec shell) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        out.putAll(StrUtil.parseKeyValueLines(shell.readFile("/proc/hwinfo")));
        out.putAll(StrUtil.parseKeyValueLines(shell.readFile("/proc/app_info")));
        return out;
    }
}
