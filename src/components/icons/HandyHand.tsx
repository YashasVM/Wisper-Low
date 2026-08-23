const WisperlowMark = ({
  width,
  height,
}: {
  width?: number | string;
  height?: number | string;
}) => (
  <svg
    width={width || 126}
    height={height || 135}
    viewBox="0 0 126 135"
    className="fill-text stroke-text"
    xmlns="http://www.w3.org/2000/svg"
  >
    <g stroke="currentColor" strokeWidth="6" strokeLinecap="round">
      <line x1="18" y1="52" x2="18" y2="83" />
      <line x1="38" y1="38" x2="38" y2="97" />
      <line x1="58" y1="22" x2="58" y2="113" />
      <line x1="78" y1="38" x2="78" y2="97" />
      <line x1="98" y1="30" x2="98" y2="105" />
      <line x1="112" y1="56" x2="112" y2="79" />
    </g>
  </svg>
);

export default WisperlowMark;
