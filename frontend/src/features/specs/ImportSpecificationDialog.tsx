import { FileUp } from 'lucide-react'
import { type DragEvent, useRef, useState } from 'react'
import { toast } from 'sonner'

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'

import {
  type ImportMessage,
  type ImportResult,
  MAX_SPEC_BYTES,
  messagesFromError,
  type SpecSource,
  useImportSpec,
  useValidateSpec,
  type ValidationResult,
} from './api'
import { ImportMessages } from './components/ImportMessages'

function formatSize(bytes: number) {
  return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.ceil(bytes / 1024)} KB`
}

export function ImportSpecificationDialog({
  projectId,
  open,
  onOpenChange,
  hasVersions,
  onImported,
}: {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  hasVersions: boolean
  onImported: (result: ImportResult) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        {/* Unmounted on close, so every opening starts clean. */}
        <ImportForm projectId={projectId} hasVersions={hasVersions} onImported={onImported} onCancel={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

function ImportForm({
  projectId,
  hasVersions,
  onImported,
  onCancel,
}: {
  projectId: string
  hasVersions: boolean
  onImported: (result: ImportResult) => void
  onCancel: () => void
}) {
  const [mode, setMode] = useState<'file' | 'text'>('file')
  const [file, setFile] = useState<File | null>(null)
  const [content, setContent] = useState('')
  const [activate, setActivate] = useState(true)
  const [dragging, setDragging] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const [check, setCheck] = useState<ValidationResult | null>(null)
  const validate = useValidateSpec(projectId)
  const importSpec = useImportSpec(projectId)

  const textBytes = new TextEncoder().encode(content).length
  const source: SpecSource | null =
    mode === 'file' ? (file ? { kind: 'file', file } : null) : content.trim() ? { kind: 'text', content } : null

  const resetResults = () => {
    setCheck(null)
    setLocalError(null)
    validate.reset()
    importSpec.reset()
  }

  const chooseFile = (chosen: File | undefined) => {
    resetResults()
    if (!chosen) {
      return
    }
    if (chosen.size > MAX_SPEC_BYTES) {
      setFile(null)
      setLocalError(`“${chosen.name}” is ${formatSize(chosen.size)}; the maximum is ${formatSize(MAX_SPEC_BYTES)}.`)
      return
    }
    setFile(chosen)
  }

  const onDrop = (event: DragEvent<HTMLButtonElement>) => {
    event.preventDefault()
    setDragging(false)
    chooseFile(event.dataTransfer.files[0])
  }

  const tooLarge = mode === 'text' && textBytes > MAX_SPEC_BYTES

  const runCheck = async () => {
    if (!source) return
    resetResults()
    try {
      setCheck(await validate.mutateAsync(source))
    } catch {
      // Shown below via validate.error.
    }
  }

  const runImport = async () => {
    if (!source) return
    resetResults()
    try {
      const result = await importSpec.mutateAsync({ source, activate: activate || !hasVersions })
      toast.success(`Imported version ${result.version.versionNumber}: ${result.version.operationCount} endpoints.`)
      onImported(result)
    } catch {
      // Shown below via importSpec.error.
    }
  }

  const rejected: ImportMessage[] = messagesFromError(importSpec.error)
  // Any failure without import messages (network, size limit, unexpected) is shown as one message.
  const requestError =
    (importSpec.error && rejected.length === 0 ? importSpec.error : null) ?? (validate.error ? validate.error : null)

  return (
    <div className="space-y-4">
      <DialogHeader>
        <DialogTitle>Import OpenAPI specification</DialogTitle>
        <DialogDescription>
          OpenAPI 3.0 or 3.1, JSON or YAML, up to {formatSize(MAX_SPEC_BYTES)}. References must point inside the same
          document. The file is read as data: nothing it mentions is ever fetched.
        </DialogDescription>
      </DialogHeader>

      <Tabs
        value={mode}
        onValueChange={(value) => {
          setMode(value as 'file' | 'text')
          resetResults()
        }}
      >
        <TabsList>
          <TabsTrigger value="file">Upload file</TabsTrigger>
          <TabsTrigger value="text">Paste text</TabsTrigger>
        </TabsList>
        <TabsContent value="file" className="pt-2">
          <button
            type="button"
            onClick={() => fileInput.current?.click()}
            onDragOver={(event) => {
              event.preventDefault()
              setDragging(true)
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={onDrop}
            className={cn(
              'flex w-full cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed px-4 py-8 text-center text-sm transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
              dragging ? 'border-primary bg-accent' : 'hover:bg-muted/60',
            )}
          >
            <FileUp className="size-6 text-muted-foreground" aria-hidden="true" />
            {file ? (
              <span>
                <span className="font-medium">{file.name}</span>{' '}
                <span className="text-muted-foreground">({formatSize(file.size)})</span>
              </span>
            ) : (
              <span>
                <span className="font-medium">Choose a file</span> or drag it here
              </span>
            )}
            <span className="text-xs text-muted-foreground">.json, .yaml or .yml</span>
          </button>
          <input
            ref={fileInput}
            id="spec-file"
            type="file"
            aria-label="Specification file"
            className="sr-only"
            accept=".json,.yaml,.yml,application/json,application/yaml,text/yaml"
            onChange={(event) => chooseFile(event.target.files?.[0])}
          />
        </TabsContent>
        <TabsContent value="text" className="space-y-1.5 pt-2">
          <Label htmlFor="spec-text">Specification text</Label>
          <Textarea
            id="spec-text"
            rows={12}
            spellCheck={false}
            className="max-h-80 font-mono text-xs"
            placeholder={'openapi: 3.0.3\ninfo:\n  title: My API\n  version: 1.0.0\npaths: {}'}
            value={content}
            onChange={(event) => {
              setContent(event.target.value)
              resetResults()
            }}
          />
          <p className={cn('text-xs', tooLarge ? 'text-destructive' : 'text-muted-foreground')}>
            {formatSize(textBytes)} of {formatSize(MAX_SPEC_BYTES)}
          </p>
        </TabsContent>
      </Tabs>

      {hasVersions ? (
        <div className="flex items-center gap-2 text-sm">
          <input
            id="spec-activate"
            type="checkbox"
            className="size-4 accent-primary"
            checked={activate}
            onChange={(event) => setActivate(event.target.checked)}
          />
          <Label htmlFor="spec-activate" className="font-normal">
            Make the new version active
          </Label>
        </div>
      ) : null}

      {localError ? (
        <Alert variant="destructive">
          <AlertDescription>{localError}</AlertDescription>
        </Alert>
      ) : null}
      {requestError ? (
        <Alert variant="destructive">
          <AlertDescription>{requestError.message}</AlertDescription>
        </Alert>
      ) : null}
      {rejected.length > 0 ? (
        <div className="max-h-72 space-y-2 overflow-y-auto">
          <p className="text-sm font-medium text-destructive" role="alert">
            {importSpec.error?.message}
          </p>
          <ImportMessages messages={rejected} label="Problems found" />
        </div>
      ) : null}
      {check ? (
        <div className="max-h-72 space-y-2 overflow-y-auto">
          {check.valid ? (
            <Alert>
              <AlertTitle>Ready to import</AlertTitle>
              <AlertDescription>
                {check.title} {check.apiVersion ? `(${check.apiVersion})` : ''} · OpenAPI {check.openapiVersion} ·{' '}
                {check.operationCount} endpoints
              </AlertDescription>
            </Alert>
          ) : (
            <p className="text-sm font-medium text-destructive" role="alert">
              This document cannot be imported.
            </p>
          )}
          <ImportMessages messages={check.messages} label={check.valid ? 'Findings' : 'Problems found'} />
        </div>
      ) : null}

      <DialogFooter>
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button
          type="button"
          variant="secondary"
          disabled={!source || tooLarge || validate.isPending || importSpec.isPending}
          onClick={() => void runCheck()}
        >
          {validate.isPending ? 'Checking…' : 'Check'}
        </Button>
        <Button
          type="button"
          disabled={!source || tooLarge || importSpec.isPending || validate.isPending}
          onClick={() => void runImport()}
        >
          {importSpec.isPending ? 'Importing…' : 'Import'}
        </Button>
      </DialogFooter>
    </div>
  )
}
