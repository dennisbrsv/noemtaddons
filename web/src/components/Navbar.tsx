import React, { useState, useEffect } from 'react';
import { Download, Disc as DiscordIcon, ExternalLink } from 'lucide-react';

export const Navbar: React.FC = () => {
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    const handleScroll = () => {
      setScrolled(window.scrollY > 20);
    };
    window.addEventListener('scroll', handleScroll);
    return () => window.removeEventListener('scroll', handleScroll);
  }, []);

  return (
    <nav
      className={`fixed top-0 left-0 right-0 z-50 border-b transition-[background-color,border-color,padding] duration-200 ease-out ${
        scrolled
          ? 'bg-[#121212]/92 backdrop-blur-md border-[#262626] py-3.5'
          : 'bg-transparent border-transparent py-5'
      }`}
    >
      <div className="max-w-5xl mx-auto px-4 sm:px-6 flex items-center justify-between">
        {/* Brand: Noemt in white, Addons in blue */}
        <a href="#" className="flex items-center group">
          <span className="font-bold text-base tracking-tight">
            <span className="text-white group-hover:text-[#F0F0F0] transition-colors">Noemt</span>
            <span className="text-[#1A73E8] group-hover:text-[#8AB4F8] transition-colors">Addons</span>
          </span>
        </a>

        {/* Action Links */}
        <div className="flex items-center gap-4 text-xs font-medium">
          <a
            href="https://discord.gg/xey9jjK4G6"
            target="_blank"
            rel="noopener noreferrer"
            className="text-[#9E9E9E] hover:text-white transition-colors flex items-center gap-1.5"
          >
            <DiscordIcon className="w-3.5 h-3.5 fill-current text-[#8AB4F8]" />
            <span className="hidden sm:inline">Discord</span>
            <ExternalLink className="w-3 h-3 opacity-60" />
          </a>

          <a
            href="/download/loader"
            className="btn-blue px-4 py-2 text-xs flex items-center gap-1.5"
          >
            <Download className="w-3.5 h-3.5" />
            <span>Download Loader</span>
          </a>
        </div>
      </div>
    </nav>
  );
};
