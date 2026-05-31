document.addEventListener('DOMContentLoaded', () => {
  const button = document.getElementById('pulse-button');
  const output = document.getElementById('pulse-output');
  button.addEventListener('click', () => {
    output.textContent = 'Pulse active';
  });
});
