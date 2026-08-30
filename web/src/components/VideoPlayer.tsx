import React, { useRef, useState, useEffect } from 'react';
import { Play, Pause, Volume2, VolumeX, Maximize2, RotateCcw } from 'lucide-react';

interface VideoPlayerProps {
  src: string;
  fallbackSrc: string;
  title: string;
  featureTag: string;
  autoPlay?: boolean;
}

export const VideoPlayer: React.FC<VideoPlayerProps> = ({
  src,
  fallbackSrc,
  title,
  featureTag,
  autoPlay = true,
}) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  
  const [isPlaying, setIsPlaying] = useState(autoPlay);
  const [isMuted, setIsMuted] = useState(true);
  const [playbackRate, setPlaybackRate] = useState(1);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [isLoading, setIsLoading] = useState(true);
  const [currentSrc, setCurrentSrc] = useState(src);
  const [showControls, setShowControls] = useState(false);

  useEffect(() => {
    setCurrentSrc(src);
    setIsLoading(true);
  }, [src]);

  const togglePlay = () => {
    if (!videoRef.current) return;
    if (isPlaying) {
      videoRef.current.pause();
      setIsPlaying(false);
    } else {
      videoRef.current.play().catch(() => {
        setIsMuted(true);
        videoRef.current?.play();
      });
      setIsPlaying(true);
    }
  };

  const toggleMute = () => {
    if (!videoRef.current) return;
    videoRef.current.muted = !isMuted;
    setIsMuted(!isMuted);
  };

  const changeSpeed = (rate: number) => {
    if (!videoRef.current) return;
    videoRef.current.playbackRate = rate;
    setPlaybackRate(rate);
  };

  const handleTimeUpdate = () => {
    if (!videoRef.current) return;
    setCurrentTime(videoRef.current.currentTime);
  };

  const handleLoadedMetadata = () => {
    if (!videoRef.current) return;
    setDuration(videoRef.current.duration);
    setIsLoading(false);
  };

  const handleSeek = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!videoRef.current) return;
    const time = parseFloat(e.target.value);
    videoRef.current.currentTime = time;
    setCurrentTime(time);
  };

  const toggleFullscreen = () => {
    if (!containerRef.current) return;
    if (document.fullscreenElement) {
      document.exitFullscreen();
    } else {
      containerRef.current.requestFullscreen().catch(() => {});
    }
  };

  const handleVideoError = () => {
    if (currentSrc !== fallbackSrc) {
      console.warn(`Local video failed for ${title}, falling back to remote stream: ${fallbackSrc}`);
      setCurrentSrc(fallbackSrc);
    } else {
      setIsLoading(false);
    }
  };

  const formatTime = (timeInSeconds: number) => {
    const mins = Math.floor(timeInSeconds / 60);
    const secs = Math.floor(timeInSeconds % 60);
    return `${mins}:${secs < 10 ? '0' : ''}${secs}`;
  };

  return (
    <div
      ref={containerRef}
      onMouseEnter={() => setShowControls(true)}
      onMouseLeave={() => setShowControls(false)}
      className="relative w-full rounded-2xl overflow-hidden bg-[#0A0A0A] border border-[#222222] group shadow-xl"
    >
      {/* Video Element */}
      <video
        ref={videoRef}
        src={currentSrc}
        autoPlay={autoPlay}
        muted={isMuted}
        loop
        playsInline
        onTimeUpdate={handleTimeUpdate}
        onLoadedMetadata={handleLoadedMetadata}
        onWaiting={() => setIsLoading(true)}
        onPlaying={() => {
          setIsLoading(false);
          setIsPlaying(true);
        }}
        onError={handleVideoError}
        className="w-full h-auto aspect-video object-cover cursor-pointer block"
        onClick={togglePlay}
      />

      {/* Top Banner */}
      <div className="absolute top-0 left-0 right-0 p-3 bg-gradient-to-b from-black/80 via-black/30 to-transparent pointer-events-none flex items-center justify-between z-30">
        <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-black/50 text-[#888888]">
          {playbackRate}x
        </span>
      </div>

      {/* Center Play Watermark on Pause */}
      {!isPlaying && (
        <div
          onClick={togglePlay}
          className="absolute inset-0 flex items-center justify-center bg-black/40 z-30 cursor-pointer"
        >
          <div className="w-14 h-14 rounded-full bg-[#1A73E8] text-white flex items-center justify-center shadow-lg transform hover:scale-105 transition-transform">
            <Play className="w-6 h-6 ml-0.5 fill-current" />
          </div>
        </div>
      )}

      {/* Loading Indicator */}
      {isLoading && (
        <div className="absolute inset-0 flex items-center justify-center bg-black/60 z-20 pointer-events-none">
          <div className="w-7 h-7 rounded-full border-2 border-[#1A73E8] border-t-transparent animate-spin" />
        </div>
      )}

      {/* Bottom Controls Bar */}
      <div
        className={`absolute bottom-0 left-0 right-0 p-3 bg-gradient-to-t from-black/90 via-black/60 to-transparent z-30 transition-opacity duration-200 ${
          showControls || !isPlaying ? 'opacity-100' : 'opacity-0'
        }`}
      >
        {/* Scrubber */}
        <div className="w-full mb-2 flex items-center">
          <input
            type="range"
            min="0"
            max={duration || 100}
            step="0.05"
            value={currentTime}
            onChange={handleSeek}
            className="w-full h-1 bg-[#262626] rounded-lg appearance-none cursor-pointer accent-[#1A73E8] hover:accent-[#4285F4]"
          />
        </div>

        <div className="flex items-center justify-between text-xs font-mono text-white/90">
          <div className="flex items-center gap-2.5">
            <button
              onClick={togglePlay}
              className="p-1 rounded hover:bg-white/10 text-white transition-colors cursor-pointer"
              title={isPlaying ? 'Pause' : 'Play'}
            >
              {isPlaying ? <Pause className="w-3.5 h-3.5" /> : <Play className="w-3.5 h-3.5 fill-current" />}
            </button>

            <button
              onClick={toggleMute}
              className="p-1 rounded hover:bg-white/10 text-white transition-colors cursor-pointer"
              title={isMuted ? 'Unmute' : 'Mute'}
            >
              {isMuted ? <VolumeX className="w-3.5 h-3.5 text-[#888888]" /> : <Volume2 className="w-3.5 h-3.5 text-[#8AB4F8]" />}
            </button>

            <span className="text-[10px] text-[#888888]">
              {formatTime(currentTime)} / {formatTime(duration)}
            </span>
          </div>

          <div className="flex items-center gap-1.5">
            <div className="flex items-center bg-black/50 rounded p-0.5 border border-white/10">
              {[0.5, 1, 1.5, 2].map((rate) => (
                <button
                  key={rate}
                  onClick={() => changeSpeed(rate)}
                  className={`px-1.5 py-0.5 rounded text-[10px] cursor-pointer ${
                    playbackRate === rate
                      ? 'bg-[#1A73E8] text-white font-bold'
                      : 'text-[#888888] hover:text-white'
                  }`}
                >
                  {rate}x
                </button>
              ))}
            </div>

            <button
              onClick={() => {
                if (videoRef.current) videoRef.current.currentTime = 0;
              }}
              className="p-1 rounded hover:bg-white/10 text-[#888888] hover:text-white transition-colors cursor-pointer"
              title="Restart"
            >
              <RotateCcw className="w-3 h-3" />
            </button>

            <button
              onClick={toggleFullscreen}
              className="p-1 rounded hover:bg-white/10 text-[#888888] hover:text-white transition-colors cursor-pointer"
              title="Fullscreen"
            >
              <Maximize2 className="w-3 h-3" />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
