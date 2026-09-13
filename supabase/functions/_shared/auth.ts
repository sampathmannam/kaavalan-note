/** Compare bearer secrets without early-exit, length, or prefix timing signals. */
export async function safeTokenEquals(
  provided: string | undefined,
  expected: string,
): Promise<boolean> {
  const hasProvidedToken = provided !== undefined;
  const encoder = new TextEncoder();
  const [providedHash, expectedHash] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(provided ?? "")),
    crypto.subtle.digest("SHA-256", encoder.encode(expected)),
  ]);
  const left = new Uint8Array(providedHash);
  const right = new Uint8Array(expectedHash);
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) {
    difference |= left[index] ^ right[index];
  }
  return hasProvidedToken && difference === 0;
}
