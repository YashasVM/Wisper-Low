import React from "react";

const WisperlowTextLogo = ({
  width,
  height,
  className,
}: {
  width?: number;
  height?: number;
  className?: string;
}) => (
  <svg
    width={width}
    height={height}
    className={className}
    viewBox="0 0 560 120"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    role="img"
    aria-label="Wisperlow"
  >
    <g fill="currentColor" className="logo-primary">
      <rect x="0" y="38" width="10" height="44" rx="5" />
      <rect x="20" y="24" width="10" height="72" rx="5" />
      <rect x="40" y="12" width="10" height="96" rx="5" />
      <rect x="60" y="30" width="10" height="60" rx="5" />
      <text
        x="92"
        y="86"
        fontFamily="system-ui, -apple-system, 'Segoe UI', sans-serif"
        fontSize="72"
        fontWeight="600"
        letterSpacing="-2"
      >
        Wisperlow
      </text>
    </g>
  </svg>
);

export default WisperlowTextLogo;
