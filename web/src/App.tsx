import React from 'react';
import { Navbar } from './components/Navbar';
import { Hero } from './components/Hero';
import { FeatureReel } from './components/FeatureReel';
import { InstallGuide } from './components/InstallGuide';
import { DevelopmentNote } from './components/DevelopmentNote';
import { Footer } from './components/Footer';

export const App: React.FC = () => {
  return (
    <div className="min-h-screen bg-[#121212] text-[#EEEEEE] selection:bg-[#1A73E8] selection:text-white flex flex-col font-['Plus_Jakarta_Sans',sans-serif]">
      <Navbar />
      <main className="flex-1">
        <Hero />
        <FeatureReel />
        <InstallGuide />
        <DevelopmentNote />
      </main>
      <Footer />
    </div>
  );
};

export default App;
