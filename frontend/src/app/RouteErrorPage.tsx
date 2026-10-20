import { isRouteErrorResponse, Link, useRouteError } from 'react-router'

import { Button } from '@/components/ui/button'

/** Shown for unknown routes and for unexpected errors thrown while rendering a route. */
export function RouteErrorPage() {
  const error = useRouteError()
  const notFound = isRouteErrorResponse(error) && error.status === 404

  return <ErrorScreen notFound={notFound} />
}

export function NotFoundPage() {
  return <ErrorScreen notFound />
}

function ErrorScreen({ notFound }: { notFound: boolean }) {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center gap-4 text-center">
      <p className="text-sm font-medium text-muted-foreground">{notFound ? '404' : 'Error'}</p>
      <h1 className="text-2xl font-semibold tracking-tight">
        {notFound ? 'Page not found' : 'Something went wrong'}
      </h1>
      <p className="max-w-md text-sm text-muted-foreground">
        {notFound
          ? 'The page you are looking for does not exist.'
          : 'An unexpected error occurred while showing this page. Reload the page or return to the overview.'}
      </p>
      <Button asChild>
        <Link to="/">Back to overview</Link>
      </Button>
    </div>
  )
}
