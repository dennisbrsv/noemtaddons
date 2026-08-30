import { FeatureData } from '../types';

export const FEATURES: FeatureData[] = [
  {
    id: 'autobloodcamp',
    title: 'AutoBloodCamp',
    subtitle: 'Blood Room Automation',
    videoSrc: '/media/autobloodcamp.mp4',
    fallbackVideoSrc: 'https://media.noemt.dev/i/ZwjKvL',
    description: 'Automatically camps the blood room for you, clearing blood mobs instantly as they spawn.',
    highlights: [
      'Automatically clears blood room',
      'Target acquisition on spawn',
      'Zero manual clicking required'
    ]
  },
  {
    id: 'autoloadoutswap',
    title: 'AutoLoadoutSwap',
    subtitle: 'Autopet for Loadouts',
    videoSrc: '/media/autoloadoutswap.mp4',
    fallbackVideoSrc: 'https://media.noemt.dev/i/F8BjB5',
    description: 'Like Autopet, but for your entire loadout. Automatically switches gear when you enter the blood room, look at a miniboss, or when the dungeon starts.',
    highlights: [
      'Swaps on blood room entry',
      'Swaps when looking at a miniboss',
      'Swaps when dungeon starts'
    ]
  },
  {
    id: 'automask',
    title: 'AutoMask',
    subtitle: 'Low Health Protection',
    videoSrc: '/media/automask.mp4',
    fallbackVideoSrc: 'https://media.noemt.dev/i/DqlLhd',
    description: 'Automatically equips a Bonzo\'s Mask or Spirit Mask when your health drops low so you don\'t die.',
    highlights: [
      'Automatic low-health detection',
      'Equips Bonzo\'s Mask or Spirit Mask',
      'Prevents avoidable deaths'
    ]
  }
];
