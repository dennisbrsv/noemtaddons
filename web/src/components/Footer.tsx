import React from 'react';
import { Download, Disc as DiscordIcon, ExternalLink } from 'lucide-react';

export const Footer: React.FC = () => {
  return (
    <footer className="border-t border-[#262626] py-10 text-xs font-mono text-[#757575] bg-[#0E0E0E]">
      <div className="max-w-5xl mx-auto px-4 sm:px-6 flex flex-col sm:flex-row items-center justify-between gap-4">
        <div className="flex items-center gap-2">
          <span className="font-bold tracking-tight">
            <span className="text-white">Noemt</span>
            <span className="text-[#1A73E8]">Addons</span>
          </span>
          <span>• Fabric 26.1.2</span>
        </div>

        <div className="flex items-center gap-5">
          <a
            href="/download/loader"
            className="hover:text-white transition-colors flex items-center gap-1 text-[#8AB4F8]"
          >
            <Download className="w-3.5 h-3.5" />
            <span>Download Loader</span>
          </a>
          <a
            href="https://discord.gg/xey9jjK4G6"
            target="_blank"
            rel="noopener noreferrer"
            className="hover:text-white transition-colors flex items-center gap-1"
          >
            <DiscordIcon className="w-3.5 h-3.5 fill-current" />
            <span>Discord</span>
            <ExternalLink className="w-3 h-3 opacity-60" />
          </a>
        </div>
      </div>
    </footer>
  );
};
