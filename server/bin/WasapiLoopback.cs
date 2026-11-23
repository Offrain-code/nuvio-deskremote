using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace NuvioAudio
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    internal class MMDeviceEnumeratorComObject { }

    [Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int dwStateMask, out IntPtr ppDevices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppEndpoint);
    }

    [Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IMMDevice
    {
        int Activate(ref Guid iid, int dwClsCtx, IntPtr pActivationParams, [MarshalAs(UnmanagedType.IUnknown)] out object ppInterface);
    }

    [Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioEndpointVolume
    {
        int RegisterControlChangeNotify(IntPtr pNotify);
        int UnregisterControlChangeNotify(IntPtr pNotify);
        int GetChannelCount(out uint pnChannelCount);
        int SetMasterVolumeLevel(float fLevelDB, ref Guid pguidEventContext);
        int SetMasterVolumeLevelScalar(float fLevel, ref Guid pguidEventContext);
        int GetMasterVolumeLevel(out float pfLevelDB);
        int GetMasterVolumeLevelScalar(out float pfLevel);
        int SetChannelVolumeLevel(uint nChannel, float fLevelDB, ref Guid pguidEventContext);
        int SetChannelVolumeLevelScalar(uint nChannel, float fLevel, ref Guid pguidEventContext);
        int GetChannelVolumeLevel(uint nChannel, out float pfLevelDB);
        int GetChannelVolumeLevelScalar(uint nChannel, out float pfLevel);
        int SetMute([MarshalAs(UnmanagedType.Bool)] bool bMute, ref Guid pguidEventContext);
        int GetMute([MarshalAs(UnmanagedType.Bool)] out bool pbMute);
    }

    [Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioClient
    {
        int Initialize(int ShareMode, uint StreamFlags, long hnsBufferDuration, long hnsPeriodicity, IntPtr pFormat, IntPtr AudioSessionGuid);
        int GetBufferSize(out uint pNumBufferFrames);
        int GetStreamLatency(out long phnsLatency);
        int GetCurrentPadding(out uint pNumPaddingFrames);
        int IsFormatSupported(int ShareMode, IntPtr pFormat, out IntPtr ppClosestMatch);
        int GetMixFormat(out IntPtr ppDeviceFormat);
        int GetDevicePeriod(out long phnsDefaultDevicePeriod, out long phnsMinimumDevicePeriod);
        int Start();
        int Stop();
        int Reset();
        int SetEventHandle(IntPtr eventHandle);
        int GetService(ref Guid riid, [MarshalAs(UnmanagedType.IUnknown)] out object ppv);
    }

    [Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IAudioCaptureClient
    {
        int GetBuffer(out IntPtr ppData, out uint pNumFramesToRead, out uint pdwFlags, out ulong pu64DevicePosition, out ulong pu64QPCPosition);
        int ReleaseBuffer(uint NumFramesRead);
        int GetNextPacketSize(out uint pNumFramesInNextPacket);
    }

    [StructLayout(LayoutKind.Sequential, Pack = 1)]
    internal struct WAVEFORMATEX
    {
        public ushort wFormatTag;
        public ushort nChannels;
        public uint nSamplesPerSec;
        public uint nAvgBytesPerSec;
        public ushort nBlockAlign;
        public ushort wBitsPerSample;
        public ushort cbSize;
    }

    internal static class Program
    {
        private const int eRender = 0;
        private const int eConsole = 0;
        private const int CLSCTX_ALL = 23;
        private const int AUDCLNT_SHAREMODE_SHARED = 0;
        private const uint AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
        private const uint AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
        private const int TARGET_RATE = 24000;

        private static IAudioEndpointVolume _endpointVol = null;
        private static bool _restored = false;

        [DllImport("Kernel32")]
        private static extern bool SetConsoleCtrlHandler(EventHandler handler, bool add);
        private delegate bool EventHandler(int sig);
        private static EventHandler _ctrlHandler;

        private static void RestoreMuteState()
        {
            if (_restored || _endpointVol == null) return;
            _restored = true;
            try
            {
                Guid empty = Guid.Empty;
                _endpointVol.SetMute(false, ref empty);
            }
            catch { }
        }

        static int Main(string[] args)
        {
            // If invoked with "--unmute", ensure the PC speaker output is unmuted and exit
            if (args != null && args.Length > 0 && args[0] == "--unmute")
            {
                try
                {
                    var enum0 = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
                    IMMDevice dev0;
                    if (enum0.GetDefaultAudioEndpoint(eRender, eConsole, out dev0) == 0 && dev0 != null)
                    {
                        Guid iidVol = new Guid("5CDF2C82-841E-4546-9722-0CF74078229A");
                        object volObj;
                        if (dev0.Activate(ref iidVol, CLSCTX_ALL, IntPtr.Zero, out volObj) == 0 && volObj != null)
                        {
                            var epVol = (IAudioEndpointVolume)volObj;
                            Guid empty = Guid.Empty;
                            epVol.SetMute(false, ref empty);
                        }
                    }
                }
                catch { }
                return 0;
            }

            try
            {
                var enumerator = (IMMDeviceEnumerator)new MMDeviceEnumeratorComObject();
                IMMDevice device;
                int hr = enumerator.GetDefaultAudioEndpoint(eRender, eConsole, out device);
                if (hr != 0 || device == null) return 2;

                // Mute the PC physical speaker output while Screen Share streams audio to the phone.
                // Under Windows CoreAudio, IAudioEndpointVolume.SetMute(true) silences the physical DAC/speakers
                // while WASAPI Loopback still delivers 100% of the pre-mute digital mixer stream.
                try
                {
                    Guid iidVol = new Guid("5CDF2C82-841E-4546-9722-0CF74078229A");
                    object volObj;
                    if (device.Activate(ref iidVol, CLSCTX_ALL, IntPtr.Zero, out volObj) == 0 && volObj != null)
                    {
                        _endpointVol = (IAudioEndpointVolume)volObj;
                        Guid empty = Guid.Empty;
                        _endpointVol.SetMute(true, ref empty);
                    }
                }
                catch { }

                _ctrlHandler = new EventHandler(sig => { RestoreMuteState(); return false; });
                SetConsoleCtrlHandler(_ctrlHandler, true);
                AppDomain.CurrentDomain.ProcessExit += (s, e) => RestoreMuteState();

                Guid iidAudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
                object clientObj;
                hr = device.Activate(ref iidAudioClient, CLSCTX_ALL, IntPtr.Zero, out clientObj);
                if (hr != 0 || clientObj == null) { RestoreMuteState(); return 3; }
                var audioClient = (IAudioClient)clientObj;

                IntPtr mixFormatPtr;
                hr = audioClient.GetMixFormat(out mixFormatPtr);
                if (hr != 0 || mixFormatPtr == IntPtr.Zero) { RestoreMuteState(); return 4; }

                WAVEFORMATEX fmt = (WAVEFORMATEX)Marshal.PtrToStructure(mixFormatPtr, typeof(WAVEFORMATEX));
                int srcRate = (int)fmt.nSamplesPerSec;
                int srcChannels = Math.Max(1, (int)fmt.nChannels);
                int bitsPerSample = fmt.wBitsPerSample;
                int blockAlign = fmt.nBlockAlign;

                bool isFloat = (fmt.wFormatTag == 3) || (fmt.wFormatTag == 0xFFFE && bitsPerSample == 32);

                // 20ms low-latency buffer
                long hnsBufferDuration = 200000;
                hr = audioClient.Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK, hnsBufferDuration, 0, mixFormatPtr, IntPtr.Zero);
                if (hr != 0) { RestoreMuteState(); return 5; }

                Guid iidCaptureClient = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
                object captureObj;
                hr = audioClient.GetService(ref iidCaptureClient, out captureObj);
                if (hr != 0 || captureObj == null) { RestoreMuteState(); return 6; }
                var captureClient = (IAudioCaptureClient)captureObj;

                hr = audioClient.Start();
                if (hr != 0) { RestoreMuteState(); return 7; }

                Stream stdout = Console.OpenStandardOutput();
                // 20ms frame at 24000Hz Stereo = 480 frames * 4 bytes = 1920 bytes
                const int chunkBytes = 1920;
                byte[] outChunk = new byte[chunkBytes];
                int outPos = 0;

                double step = (double)srcRate / TARGET_RATE;
                double srcPosFrac = 0.0;

                byte[] rawBuf = new byte[65536];

                // Automatic Gain Control (AGC + 3.2x preamp with soft limiter)
                // Keeps mobile speaker playback loud and balanced even when PC player volume is low
                float agcGain = 3.2f;

                while (true)
                {
                    uint nextPacketSize;
                    captureClient.GetNextPacketSize(out nextPacketSize);
                    if (nextPacketSize == 0)
                    {
                        Thread.Sleep(5);
                        continue;
                    }

                    while (nextPacketSize > 0)
                    {
                        IntPtr dataPtr;
                        uint numFrames;
                        uint flags;
                        ulong devPos, qpcPos;
                        hr = captureClient.GetBuffer(out dataPtr, out numFrames, out flags, out devPos, out qpcPos);
                        if (hr != 0) break;

                        bool silent = (flags & AUDCLNT_BUFFERFLAGS_SILENT) != 0 || dataPtr == IntPtr.Zero;
                        int totalBytes = (int)(numFrames * (uint)blockAlign);
                        if (!silent && totalBytes > 0)
                        {
                            if (rawBuf.Length < totalBytes) rawBuf = new byte[totalBytes * 2];
                            Marshal.Copy(dataPtr, rawBuf, 0, totalBytes);
                        }

                        captureClient.ReleaseBuffer(numFrames);

                        // Measure block peak to adjust AGC without distortion
                        float blockPeak = 0.001f;
                        if (!silent && isFloat && bitsPerSample == 32)
                        {
                            for (int f = 0; f < numFrames; f += 4)
                            {
                                int bOff = f * blockAlign;
                                float aL = Math.Abs(BitConverter.ToSingle(rawBuf, bOff));
                                if (aL > blockPeak) blockPeak = aL;
                            }
                            float targetGain = 0.88f / blockPeak;
                            if (targetGain > 6.5f) targetGain = 6.5f;
                            if (targetGain < 1.4f) targetGain = 1.4f;
                            // Fast attack on peaks, smooth release on quiet passages
                            if (targetGain < agcGain)
                                agcGain = agcGain * 0.70f + targetGain * 0.30f;
                            else
                                agcGain = agcGain * 0.96f + targetGain * 0.04f;
                        }

                        // Resample from srcRate to 24000Hz Stereo s16le with preamp + soft limiter
                        while (srcPosFrac < numFrames)
                        {
                            int frameIdx = (int)srcPosFrac;
                            short left = 0;
                            short right = 0;

                            if (!silent && frameIdx < numFrames)
                            {
                                int baseOff = frameIdx * blockAlign;
                                if (isFloat && bitsPerSample == 32)
                                {
                                    float fl = BitConverter.ToSingle(rawBuf, baseOff) * agcGain;
                                    float fr = (srcChannels >= 2 ? BitConverter.ToSingle(rawBuf, baseOff + 4) : BitConverter.ToSingle(rawBuf, baseOff)) * agcGain;

                                    // Clamp softly to prevent harsh digital clipping
                                    if (fl > 1.0f) fl = 1.0f; else if (fl < -1.0f) fl = -1.0f;
                                    if (fr > 1.0f) fr = 1.0f; else if (fr < -1.0f) fr = -1.0f;

                                    left = (short)(fl * 32760.0f);
                                    right = (short)(fr * 32760.0f);
                                }
                                else if (bitsPerSample == 16)
                                {
                                    int il = (int)(BitConverter.ToInt16(rawBuf, baseOff) * 2.8f);
                                    int ir = (int)((srcChannels >= 2 ? BitConverter.ToInt16(rawBuf, baseOff + 2) : BitConverter.ToInt16(rawBuf, baseOff)) * 2.8f);
                                    if (il > 32767) il = 32767; else if (il < -32768) il = -32768;
                                    if (ir > 32767) ir = 32767; else if (ir < -32768) ir = -32768;
                                    left = (short)il;
                                    right = (short)ir;
                                }
                            }

                            outChunk[outPos++] = (byte)(left & 0xFF);
                            outChunk[outPos++] = (byte)((left >> 8) & 0xFF);
                            outChunk[outPos++] = (byte)(right & 0xFF);
                            outChunk[outPos++] = (byte)((right >> 8) & 0xFF);

                            if (outPos >= chunkBytes)
                            {
                                stdout.Write(outChunk, 0, chunkBytes);
                                stdout.Flush();
                                outPos = 0;
                            }

                            srcPosFrac += step;
                        }
                        srcPosFrac -= numFrames;
                        if (srcPosFrac < 0) srcPosFrac = 0;

                        captureClient.GetNextPacketSize(out nextPacketSize);
                    }
                }
            }
            catch
            {
                RestoreMuteState();
                return 1;
            }
        }
    }
}
