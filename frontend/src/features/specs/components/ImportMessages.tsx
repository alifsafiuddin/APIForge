import { CircleAlert, Info, TriangleAlert } from 'lucide-react'

import type { ImportMessage } from '../api'

const ICONS = {
  ERROR: <CircleAlert className="size-4 shrink-0 text-destructive" aria-hidden="true" />,
  WARNING: <TriangleAlert className="size-4 shrink-0 text-amber-600" aria-hidden="true" />,
  INFO: <Info className="size-4 shrink-0 text-sky-600" aria-hidden="true" />,
}

const LABELS = { ERROR: 'Error', WARNING: 'Warning', INFO: 'Note' }

/** Import findings, errors first, each with its location in the document. */
export function ImportMessages({ messages, label }: { messages: ImportMessage[]; label: string }) {
  if (messages.length === 0) {
    return null
  }
  const order = { ERROR: 0, WARNING: 1, INFO: 2 }
  const sorted = [...messages].sort((a, b) => order[a.severity] - order[b.severity])
  return (
    <ul aria-label={label} className="divide-y rounded-lg border text-sm">
      {sorted.map((message, index) => (
        <li key={`${message.code}-${index}`} className="flex gap-3 px-3 py-2.5">
          {ICONS[message.severity]}
          <div className="min-w-0 space-y-0.5">
            <p>
              <span className="sr-only">{LABELS[message.severity]}: </span>
              {message.message}
            </p>
            {message.location ? (
              <p className="font-mono text-xs break-all text-muted-foreground">{message.location}</p>
            ) : null}
          </div>
        </li>
      ))}
    </ul>
  )
}
