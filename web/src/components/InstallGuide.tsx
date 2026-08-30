import React, { useState } from 'react';
import { Download, Copy, Check } from 'lucide-react';

export const InstallGuide: React.FC = () => {
  const [copied, setCopied] = useState(false);

  const copyCommand = () => {
    navigator.clipboard.writeText('/noemt');
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <section className="py-20 md:py-28 border-t border-[#262626]">
      <div className="max-w-4xl mx-auto px-4 sm:px-6">
        <div className="text-center max-w-xl mx-auto mb-14 space-y-2.5">
          <h2 className="text-2xl sm:text-3xl font-bold text-[#EEEEEE] tracking-tight">
            Installation &amp; Setup
          </h2>
          <p className="text-sm text-[#9E9E9E]">
            Get running in three simple steps.
          </p>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-3 gap-8 text-left text-xs">
          <div className="space-y-2">
            <span className="font-mono text-[#1A73E8] font-bold text-base block">01</span>
            <span className="font-semibold text-sm text-[#EEEEEE] block">Download Loader</span>
            <p className="text-[#888888] leading-relaxed">
              Download the official <code className="text-[#8AB4F8]">noemtaddons-loader.jar</code>.
            </p>
            <div className="pt-2">
              <a
                href="/download/loader"
                className="btn-blue px-3.5 py-1.5 text-xs inline-flex items-center gap-1.5"
              >
                <Download className="w-3.5 h-3.5" />
                <span>Get Loader</span>
              </a>
            </div>
          </div>

          <div className="space-y-2">
            <span className="font-mono text-[#1A73E8] font-bold text-base block">02</span>
            <span className="font-semibold text-sm text-[#EEEEEE] block">Add to Mods</span>
            <p className="text-[#888888] leading-relaxed">
              Place the jar into your <code className="text-[#BBBBBB]">.minecraft/mods</code> folder and start Fabric 26.1.2.
            </p>
          </div>

          <div className="space-y-2">
            <span className="font-mono text-[#1A73E8] font-bold text-base block">03</span>
            <span className="font-semibold text-sm text-[#EEEEEE] block">Configure In-Game</span>
            <p className="text-[#888888] leading-relaxed">
              Open the settings menu in chat using the command below:
            </p>
            <div className="pt-2">
              <button
                onClick={copyCommand}
                className="px-3 py-1.5 rounded-lg bg-[#1E1E1E] hover:bg-[#282828] border border-[#2C2C2C] text-[#8AB4F8] font-mono text-xs flex items-center gap-1.5 cursor-pointer transition-colors"
              >
                {copied ? <Check className="w-3.5 h-3.5 text-[#34D399]" /> : <Copy className="w-3.5 h-3.5" />}
                <span>{copied ? 'Copied (/noemt)' : '/noemt'}</span>
              </button>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
};
