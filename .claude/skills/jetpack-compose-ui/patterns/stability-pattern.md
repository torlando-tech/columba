# Stability Pattern Transformations

## Pattern 1: Unstable Collection Parameters

### ❌ Before (Always Recomposes)

```kotlin
@Composable
fun UserList(users: List<User>) {
    // Will NOT skip recomposition even if users hasn't changed
    // Because List is not marked @Stable
    LazyColumn {
        items(users) { user ->
            UserRow(user)
        }
    }
}
```

**Compiler Report**: `unstable users: List<User>`

### ✅ After Option 1 (ImmutableList)

```kotlin
// Add dependency: org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.5

@Composable
fun UserList(users: ImmutableList<User>) {
    // Will skip recomposition if users hasn't changed
    LazyColumn {
        items(users, key = { it.id }) { user ->
            UserRow(user)
        }
    }
}

// In ViewModel
val users: StateFlow<ImmutableList<User>> = repository.usersFlow
    .map { it.toImmutableList() }
    .stateIn(...)
```

### ✅ After Option 2 (@Stable Wrapper)

```kotlin
@Stable
data class UserList(val users: List<User>)

@Composable
fun UserListScreen(userList: UserList) {
    // Will skip recomposition if userList hasn't changed
    LazyColumn {
        items(userList.users, key = { it.id }) { user ->
            UserRow(user)
        }
    }
}
```

---

## Pattern 2: No Keys on LazyColumn Items

### ❌ Before (Entire List Recomposes)

```kotlin
LazyColumn {
    items(notes) { note ->
        NoteRow(note)
    }
}
```

**Problem**: Without keys, Compose can't identify which items changed. All items recompose.

### ✅ After (Only Changed Items Recompose)

```kotlin
LazyColumn {
    items(
        items = notes,
        key = { note -> note.id } // ✅ Stable, unique identifier
    ) { note ->
        NoteRow(note)
    }
}
```

**Benefits**:
- Only changed items recompose (10x faster for large lists)
- Scroll position preserved
- Item animations work correctly

---

## Pattern 3: Expensive Calculation in Composition

### ❌ Before (Recalculates Every Recomposition)

```kotlin
@Composable
fun ContactList(contacts: List<Contact>, comparator: Comparator<Contact>) {
    LazyColumn {
        // ❌ Sorts on EVERY recomposition
        items(contacts.sortedWith(comparator)) { contact ->
            ContactRow(contact)
        }
    }
}
```

**Problem**: Even if `contacts` and `comparator` haven't changed, sorting happens on every recomposition.

### ✅ After (Cached with remember)

```kotlin
@Composable
fun ContactList(contacts: List<Contact>, comparator: Comparator<Contact>) {
    // ✅ Only recalculates when contacts or comparator change
    val sortedContacts = remember(contacts, comparator) {
        contacts.sortedWith(comparator)
    }

    LazyColumn {
        items(sortedContacts, key = { it.id }) { contact ->
            ContactRow(contact)
        }
    }
}
```

---

## Pattern 4: Mutable Properties in Data Classes

### ❌ Before (Unstable, Always Recomposes)

```kotlin
data class Contact(
    var name: String,
    var number: String
)

@Composable
fun ContactRow(contact: Contact) {
    // Always recomposes because Contact is unstable (var properties)
}
```

**Compiler Report**: `unstable contact: Contact`

### ✅ After (Immutable, Skips When Unchanged)

```kotlin
data class Contact(
    val name: String,  // ✅ val not var
    val number: String // ✅ val not var
)

@Composable
fun ContactRow(contact: Contact) {
    // Skips recomposition when contact hasn't changed
}
```

**Compiler Report**: `stable contact: Contact`

---

## Pattern 5: Reading State in Composition Phase

### ❌ Before (Triggers Full Composition)

```kotlin
@Composable
fun ParallaxImage() {
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    Image(
        modifier = Modifier.offset(
            // ❌ Reads in composition phase
            with(density) {
                (listState.firstVisibleItemScrollOffset / 2).toDp()
            }
        )
    )
}
```

**Problem**: Every scroll pixel triggers Composition → Layout → Drawing (all 3 phases).

### ✅ After (Defers to Layout Phase)

```kotlin
@Composable
fun ParallaxImage() {
    val listState = rememberLazyListState()

    Image(
        modifier = Modifier.offset {
            // ✅ Reads in layout phase via lambda
            IntOffset(x = 0, y = listState.firstVisibleItemScrollOffset / 2)
        }
    )
}
```

**Result**: Every scroll pixel triggers Layout → Drawing (only 2 phases, 5-10x faster).

---

## Pattern 6: No derivedStateOf for Threshold Values

### ❌ Before (Recomposes on Every Scroll Pixel)

```kotlin
@Composable
fun ScrollableScreen() {
    val listState = rememberLazyListState()
    val showButton = listState.firstVisibleItemIndex > 0 // ❌ Recomposes 60+ times/second

    AnimatedVisibility(visible = showButton) {
        ScrollToTopButton()
    }
}
```

**Problem**: Recomposes on every scroll pixel (60+ per second) even though boolean only changes twice.

### ✅ After (Only Recomposes When Boolean Changes)

```kotlin
@Composable
fun ScrollableScreen() {
    val listState = rememberLazyListState()

    val showButton by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0
        }
    }

    AnimatedVisibility(visible = showButton) {
        ScrollToTopButton()
    }
}
```

**Result**: Only 2 recompositions total (false→true, true→false).

---

## Pattern 7: Unstable Painter Parameters

### ❌ Before (Painter is Unstable)

```kotlin
@Composable
fun MyImage(painter: Painter) {
    Image(painter = painter, contentDescription = null)
}
```

**Problem**: Painter is not marked @Stable, causes unnecessary recompositions.

### ✅ After (Pass Stable Parameters)

```kotlin
@Composable
fun MyImage(@DrawableRes imageRes: Int) {
    val painter = painterResource(imageRes)
    Image(painter = painter, contentDescription = null)
}

// Or for URLs
@Composable
fun MyImage(url: String) {
    val painter = rememberAsyncImagePainter(url) // Coil library
    Image(painter = painter, contentDescription = null)
}
```

---

## Quick Reference

| Problem | Solution | Benefit |
|---------|----------|---------|
| Unstable List parameter | Use `ImmutableList` or `@Stable` wrapper | Smart skipping |
| No keys on LazyColumn | Add `key = { it.id }` | Only changed items recompose |
| Expensive calculation | Wrap in `remember(deps) { }` | Cache result |
| Mutable properties | Use `val` not `var` | Stability |
| Reading state in composition | Use lambda modifiers `Modifier.offset { }` | Skip composition phase |
| Threshold-based state | Use `derivedStateOf` | Reduce recompositions |
| Unstable Painter | Pass resource ID or URL string | Stability |

## Verification

Generate compiler reports to verify:

```kotlin
// build.gradle.kts
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

Look for `restartable skippable` (good) vs `restartable unskippable` (bad).
