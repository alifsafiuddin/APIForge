import { useEffect, useState } from 'react'

/** Seconds since `running` became true (for long local model calls). */
export function useElapsedSeconds(running: boolean) {
  const [seconds, setSeconds] = useState(0)
  useEffect(() => {
    if (!running) return
    const started = Date.now()
    const timer = window.setInterval(() => setSeconds(Math.floor((Date.now() - started) / 1000)), 1000)
    return () => {
      window.clearInterval(timer)
      setSeconds(0)
    }
  }, [running])
  return seconds
}
