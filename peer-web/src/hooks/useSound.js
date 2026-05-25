/**
 * useSound — play notification sounds using actual audio files.
 * Reads the 'sound_enabled' key from localStorage so the setting persists across reloads.
 */
import { useCallback, useRef } from 'react';

const STORAGE_KEY = 'p2pchat_sound_enabled';

export function useSoundEnabled() {
  const raw = localStorage.getItem(STORAGE_KEY);
  return raw === null ? true : raw === 'true'; // default ON
}

export function setSoundEnabled(enabled) {
  localStorage.setItem(STORAGE_KEY, String(enabled));
}

export function useSound() {
  // Pre-load the audio element for better performance
  const audioRef = useRef(new Audio('/sound/universfield-new-notification-09-352705.mp3'));

  const playSound = useCallback((soundType) => {
    if (!useSoundEnabled()) return;
    
    const audio = audioRef.current;
    if (!audio) return;
    
    // Reset playback position if it's already playing
    audio.currentTime = 0;
    audio.play().catch(e => {
      // Browsers block autoplay until the user interacts with the page.
      // Silently ignore if playing fails.
      console.debug("Audio play prevented:", e);
    });
  }, []);

  return playSound;
}
