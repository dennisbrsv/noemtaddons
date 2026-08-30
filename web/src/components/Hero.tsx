import React from 'react';
import { Download, Disc as DiscordIcon, ExternalLink } from 'lucide-react';

export const Hero: React.FC = () => {
  return (
    <section className="pt-32 pb-16 md:pt-40 md:pb-20">
      <div className="max-w-4xl mx-auto px-4 sm:px-6 text-center">
        <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-[#1E1E1E] border border-[#2C2C2C] text-[#A0A0A0] text-xs font-medium mb-6">
          <span className="w-1.5 h-1.5 rounded-full bg-[#1A73E8]" />
          <span>Fabric 26.1.2 • Hypixel Skyblock</span>
        </div>

        <h1 className="text-4xl sm:text-6xl font-extrabold tracking-tight text-[#EEEEEE] mb-5 leading-[1.15]">
          Hypixel Skyblock <br />
          <span className="text-[#8AB4F8]">Dungeon Utility</span>
        </h1>

        <div className="flex flex-col sm:flex-row items-center justify-center gap-3">
          <a
            href="/download/loader"
            className="w-full sm:w-auto btn-blue px-7 py-3 text-sm flex items-center justify-center gap-2 cursor-pointer"
          >
            <Download className="w-4 h-4" />
            <span>Download Loader (.jar)</span>
          </a>

          <a
            href="https://discord.gg/xey9jjK4G6"
            target="_blank"
            rel="noopener noreferrer"
            className="w-full sm:w-auto btn-outlined px-6 py-3 text-sm flex items-center justify-center gap-2 cursor-pointer"
          >
            <DiscordIcon className="w-4 h-4 fill-current text-[#8AB4F8]" />
            <span>Join Discord</span>
            <ExternalLink className="w-3.5 h-3.5 opacity-60" />
          </a>
        </div>
      </div>
    </section>
  );
};
