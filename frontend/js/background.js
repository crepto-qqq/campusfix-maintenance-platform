(() => {
  const layer = document.createElement('div');
  layer.className = 'site-background';
  layer.setAttribute('aria-hidden', 'true');

  const poster = document.createElement('img');
  poster.className = 'site-background-media';
  poster.src = './media/campus-background.jpg';
  poster.alt = '';
  layer.append(poster);

  const video = document.createElement('video');
  video.className = 'site-background-media';
  video.muted = true;
  video.loop = true;
  video.playsInline = true;
  video.preload = 'none';
  video.tabIndex = -1;
  layer.append(video);
  document.body.prepend(layer);

  const toggle = document.createElement('button');
  toggle.className = 'background-toggle';
  toggle.type = 'button';
  toggle.hidden = true;
  document.body.append(toggle);

  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const connection = navigator.connection;
  let pausedByUser = false;
  let failed = false;

  function updateToggle() {
    const label = video.paused ? 'Play background animation' : 'Pause background animation';
    toggle.setAttribute('aria-label', label);
    toggle.title = label;
    const path = video.paused ? 'M6 3l12 9-12 9z' : 'M5 4h5v16H5zm9 0h5v16h-5z';
    toggle.innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${path}"/></svg>`;
  }

  function updatePlayback() {
    const staticOnly = reducedMotion.matches || connection?.saveData;
    if (staticOnly || document.hidden || pausedByUser || failed) {
      video.pause();
      if (staticOnly || failed) toggle.hidden = true;
      return;
    }
    if (!video.getAttribute('src')) video.src = './media/campus-background.mp4';
    video.play().catch(() => {
      // The still image remains visible if the browser blocks autoplay.
      updateToggle();
      toggle.hidden = failed || reducedMotion.matches || Boolean(connection?.saveData);
    });
  }

  video.addEventListener('playing', () => {
    layer.classList.add('is-playing');
    toggle.hidden = false;
    updateToggle();
  });
  video.addEventListener('pause', updateToggle);
  video.addEventListener('error', () => {
    failed = true;
    layer.classList.remove('is-playing');
    toggle.hidden = true;
  });
  toggle.addEventListener('click', () => {
    pausedByUser = !video.paused;
    updatePlayback();
  });
  document.addEventListener('visibilitychange', updatePlayback);
  reducedMotion.addEventListener('change', updatePlayback);
  connection?.addEventListener('change', updatePlayback);
  updatePlayback();
})();
