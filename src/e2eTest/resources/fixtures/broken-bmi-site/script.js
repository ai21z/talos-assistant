document.getElementById('bmi-form').addEventListener('submit', function (event) {
  event.preventDefault();
  const weight = parseFloat(document.getElementById('weight').value);
  const height = parseFloat(document.getElementById('height').value);
  const bmi = weight / ((height / 100) * (height / 100));
  document.getElementById('bmi-result').textContent = bmi.toFixed(2);
});
