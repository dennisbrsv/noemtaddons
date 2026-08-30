import React from 'react';
import { Disc as DiscordIcon, ExternalLink } from 'lucide-react';

export const DevelopmentNote: React.FC = () => {
  return (
    <section className="py-20 md:py-24 border-t border-[#262626]">
      <div className="max-w-xl mx-auto px-4 sm:px-6 text-center space-y-4">
        <h3 className="text-xl font-bold text-[#EEEEEE] tracking-tight">
          Active Development
        </h3>

        <p className="text-sm text-[#9E9E9E] leading-relaxed">
          There are currently no other features really, but I'm working on it and will extend to add more stuff as I get annoyed.
        </p>

        <div className="pt-2">
          <a
            href="https://discord.gg/xey9jjK4G6"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-2 btn-secondary px-5 py-2.5 text-xs font-medium cursor-pointer"
          >
            <DiscordIcon className="w-3.5 h-3.5 fill-current text-[#8AB4F8]" />
            <span>Join Discord for Updates</span>
            <ExternalLink className="w-3 h-3 opacity-60" />
          </a>
        </div>
      </div>
    </section>
  );
};
