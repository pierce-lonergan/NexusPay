/**
 * LoadingSkeleton — full-page skeleton for initial checkout load.
 */

export function LoadingSkeleton() {
  return (
    <div className="skeleton-page" aria-busy="true" aria-label="Loading checkout">
      <div className="skeleton-page__header">
        <div className="skeleton skeleton--circle" style={{ width: 32, height: 32 }} />
        <div className="skeleton skeleton--text" style={{ width: 160, height: 20 }} />
      </div>

      <div className="skeleton-page__layout">
        <div className="skeleton-page__form">
          <div className="skeleton skeleton--text" style={{ width: '40%', height: 16, marginBottom: 12 }} />
          <div className="skeleton skeleton--block" style={{ height: 160, marginBottom: 24 }} />
          <div className="skeleton skeleton--text" style={{ width: '30%', height: 16, marginBottom: 12 }} />
          <div className="skeleton skeleton--block" style={{ height: 240, marginBottom: 24 }} />
          <div className="skeleton skeleton--block" style={{ height: 48 }} />
        </div>

        <div className="skeleton-page__summary">
          <div className="skeleton skeleton--text" style={{ width: '60%', height: 16, marginBottom: 16 }} />
          <div className="skeleton skeleton--block" style={{ height: 2, marginBottom: 16 }} />
          <div className="skeleton skeleton--text" style={{ width: '100%', height: 20 }} />
        </div>
      </div>
    </div>
  );
}
