import type { ImportMessage } from './api'

export function countBySeverity(messages: ImportMessage[]) {
  return {
    errors: messages.filter((message) => message.severity === 'ERROR').length,
    warnings: messages.filter((message) => message.severity === 'WARNING').length,
    notes: messages.filter((message) => message.severity === 'INFO').length,
  }
}
