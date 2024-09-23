# Futura

## Foreword

Futura is an all-in-one utility to manage your multithreaded applications. 
It provides a simple and easy-to-use interface to manage your threads, contexts, stages, and much more. 
<br>

Futura's deal-breaker is its replacement of the traditional `CompletableFuture`, provided by Java.
It utilises a more broad and flexible API, featuring ideas from the `Node.js` Promises API.
<br>

Managing multithreaded applications might be hard to implement properly without the use of a universal tool.
Futura lets you handle asynchronous callbacks, states and context management in a more developer-friendly way.

## Basic usage

### Defining asynchronous callbacks

```java
Future<String> getUsernameById(String userId) {
    return Future.completeAsync(() -> {
        // perform synchronous operation to get the user
        User user = userRepository.findById(userId);
        return user.getName();
    });
}

void handleUserAction(String userId) {
    String name = getUsernameById(userId)
        .timeout(3, TimeUnit.SECONDS)
        .fallback("couldn't find username")
        .await();
    
    System.out.println("Welcome, " + name);
}
```

### Handling exceptions gracefully

Although Java lets you specify exceptions in the method signature, runtime exceptions might surprise you
when calling a method that throws a runtime exception.
<br>

A future explicitly states that the method might fail during runtime, therefore letting the caller
decide, how to handle the exception. You may choose to handle the exception immediately, or explicitly tell
the parent method to handle the exception.

```java
Future<Integer> doSomething() {
    return Future.tryComplete(() -> dangerousOperation());
}

void myMethod() {
    int value = doSomething().fallback(myFallbackValue).await(); // fallback to a default value, if the operation fails
    
    try {
        int value2 = doSomething().get(); // must handle the exception immediately
    } catch (FutureExecutionException e) {
        Logger.error("An error occurred while executing the operation", e);
    }
    
    int value3 = doSomething().filter(x -> x >= 3).fallback(3).await(); // filter the result, and fallback to a default value if the filter fails 
}
```

If you do not wish to handle the exception immediately, you may choose to let the parent method handle the exception.
By returning a Future, you tell the caller method, that your operation might fail, and the caller should handle the exception.
If no error occurs, the code runs as expected.
<br>

This way, you don't have to have try-catches everywhere in your methods. You may choose to handle multiple exceptions in one place.

```java
Future<Integer> doSomething() {
    return Future.tryComplete(() -> dangerousOperation());
}

Future<Integer> doSomethingElse() {
    return Future.tryComplete(() -> {
        int value = doSomething().await(); // this might fail, let the caller method handle the error
        return value * 2;
    });
}
```

### Functional Future chaining

You can enjoy the features like the ones provided by the Stream API in Java, but with asynchronous operations.
You can chain multiple operations, filter the results, transform the results, and much more.

```java
void handleUsername() {
    getUsernameById("123")
        .timeout(5, TimeUnit.SECONDS) // getUsernameById might take too long, timeout after 5 seconds
        .filter(name -> name.startsWith("abc")) // username must begin with "abc"
        .trasform(String::toUpperCase) // transform the username to uppercase
        .tryThen(userService::validateUsername) // fail the Future implicitly, if username validation fails
        .fallback("not found") // if the operation fails, return "not found"
        .await(); // block until the Future completes or fails
}
```

### Non-blocking Future callbacks

You may want to handle Future completion or failure without blocking the current thread.

```java
void doSomething() {
    doSomethingAsync()
        // handle completion value without blocking the current thread
        .then(value -> System.out.println("Operation completed with result: " + value))
        // handle failure without blocking the current thread
        .except(error -> System.out.println("Operation completed"))
        // handle both cases without blocking the current thread
        .result((value, error) -> System.out.println("Completion result: " + value + " | " + error));
}
```

Your callbacks may do some heavy operations, therefore you may want to run them asynchronously as well.

```java
void doSomethingElse() {
    doSomethingAsync()
        .thenAsync(value -> doSomethingHeavy(value));
}
```

### Cross-context Future completing

In some cases, you may want to complete the Future from another (possibly asynchronous) context.
You can use a Promise-like API, that is similar to `Node.js`.

```java
Future<User> getUserById(long userId) {
    return Future.tryResolve(resolver -> {
        // using tryResolve, to capture this exception by the Future
        if (userId < 0 || userId > 100) 
            throw new IllegalArgumentException("Invalid user ID");
        
        // handle logic on another thread that will complete this Future
        userRepository.findByIdAsync(userId, user -> {
            if (user == null)
                resolver.fail(new IllegalStateException("User not found"));
            else
                resolver.complete(user);
        });
    });
}
```
