import React from 'react';
import { FEATURES } from '../data/features';
import { VideoPlayer } from './VideoPlayer';
import { CheckCircle2 } from 'lucide-react';

export const FeatureReel: React.FC = () => {
  return (
    <section className="py-16 md:py-24 space-y-24 md:space-y-32">
      <div className="max-w-5xl mx-auto px-4 sm:px-6 space-y-24 md:space-y-32">
        {FEATURES.map((feature, index) => {
          const isEven = index % 2 === 1;
          return (
            <div
              key={feature.id}
              className="grid grid-cols-1 lg:grid-cols-12 gap-8 lg:gap-12 items-center"
            >
              {/* Video Player (7 cols) */}
              <div className={`lg:col-span-7 ${isEven ? 'lg:order-2' : 'lg:order-1'}`}>
                <VideoPlayer
                  src={feature.videoSrc}
                  fallbackSrc={feature.fallbackVideoSrc}
                  title="{feature.title}"
                  featureTag={feature.subtitle}
                />
              </div>

              {/* Description & Details (5 cols) */}
              <div className={`lg:col-span-5 space-y-3.5 ${isEven ? 'lg:order-1' : 'lg:order-2'}`}>
                <span className="text-xs font-mono text-[#8AB4F8] uppercase tracking-wider font-semibold block">
                  {feature.subtitle}
                </span>

                <h2 className="text-2xl sm:text-3xl font-bold text-[#EEEEEE] tracking-tight">
                  {feature.title}
                </h2>

                <p className="text-sm text-[#9E9E9E] leading-relaxed">
                  {feature.description}
                </p>

                <div className="space-y-2 pt-2 text-xs text-[#CCCCCC]">
                  {feature.highlights.map((highlight, idx) => (
                    <div key={idx} className="flex items-center gap-2.5">
                      <CheckCircle2 className="w-3.5 h-3.5 text-[#1A73E8] shrink-0" />
                      <span>{highlight}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
};
